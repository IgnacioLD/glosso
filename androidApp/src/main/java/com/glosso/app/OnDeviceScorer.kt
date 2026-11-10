package com.glosso.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Base64
import com.glosso.app.api.GlossoApi
import com.glosso.app.api.ScoreRequest
import com.glosso.app.api.downloadTo
import com.glosso.app.audio.AudioTranscoder
import com.glosso.app.download.DownloadFailure
import com.glosso.app.download.DownloadFile
import com.glosso.app.download.DownloadItem
import com.glosso.app.download.DownloadItemState
import com.glosso.app.download.DownloadKind
import com.glosso.app.download.DownloadManager
import com.glosso.app.download.DownloadPriority
import com.glosso.app.platform.PickedFile
import com.glosso.app.resources.*
import com.glosso.engine.accent.AccentScoring
import com.glosso.engine.accent.AccentTarget
import com.glosso.engine.model.PhonemeModelConfig
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.ProsodyBonusModel
import com.glosso.engine.phonetics.AudioAnalysis
import com.glosso.engine.phonetics.FeedbackAssembler
import com.glosso.engine.phonetics.ModelState
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.PhoneticProfile
import com.glosso.engine.phonetics.ProsodyBonus
import com.glosso.engine.phonetics.StressAnalyzer
import com.glosso.engine.phonetics.Wav2Vec2PhonemeRecognizer
import java.io.File
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString

/**
 * Optional offline pronunciation scoring. The acoustic model and its vocabulary
 * are downloaded on demand, stored in the app's private storage, and loaded into
 * memory to score with the exact same engine the cloud uses. When the pack is
 * absent the scorer is simply unavailable and the caller falls back to the cloud.
 *
 * The model is large (a few hundred MB), which is why this is an explicit,
 * removable download rather than something bundled with every lesson.
 */
object OnDeviceScorer : OnDeviceScoring {

    private const val DIR = "scoring"

    /** Approximate download size shown before the pack exists. */
    const val APPROX_BYTES = OnDeviceScoring.APPROX_BYTES

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loadMutex = Mutex()

    private lateinit var root: File
    private lateinit var appContext: Context

    @Volatile
    private var recognizer: Wav2Vec2PhonemeRecognizer? = null

    private val _state = MutableStateFlow(OnDeviceScorerState())
    override val state: StateFlow<OnDeviceScorerState> = _state.asStateFlow()

    fun init(context: Context) {
        DownloadManager.init(context)
        if (::root.isInitialized) return
        appContext = context.applicationContext
        root = File(context.filesDir, DIR)
        appContext.registerComponentCallbacks(releaseInBackground)
        scope.launch { refresh() }
    }

    /**
     * The loaded model is the largest thing the app holds (hundreds of MB of native
     * memory). Once the app is in the background list it is released, so the system
     * does not have to kill the process to get it back; the next take reloads it.
     */
    private val releaseInBackground = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && recognizer != null) scope.launch { unload() }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Required by ComponentCallbacks; onTrimMemory covers it.")
        override fun onLowMemory() = Unit
    }

    private fun modelFile() = File(root, PhonemeModelConfig.MODEL_FILE)
    private fun vocabFile() = File(root, PhonemeModelConfig.VOCAB_FILE)

    private fun packPresent() = modelFile().length() > 0 && vocabFile().length() > 0
    private fun packBytes() = listOf(modelFile(), vocabFile()).filter { it.isFile }.sumOf { it.length() }

    /** True when the model pack is on the device, loaded or not. */
    override fun available(): Boolean = ::root.isInitialized && packPresent()

    private fun readyNow(): Boolean = recognizer?.state == ModelState.READY

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(
            loaded = true,
            present = packPresent(),
            ready = readyNow(),
            bytes = packBytes(),
        )
    }

    /**
     * Downloads the model and vocabulary through the shared background queue, so
     * it survives the learner leaving the app and shows the same notification,
     * pause/resume and Wi-Fi rules as every other download.
     */
    override suspend fun download() = download(appApi)

    override suspend fun download(api: GlossoApi): Unit = withContext(Dispatchers.IO) {
        if (_state.value.downloading) return@withContext
        // Starting the pack from any surface spends the one-time offline tip, so
        // a later cancel or failure cannot make the offer pop back up.
        OfflineModelHint.markSeen(appContext)
        try {
            root.mkdirs()
            val files = listOf(PhonemeModelConfig.MODEL_FILE, PhonemeModelConfig.VOCAB_FILE).map { name ->
                val target = File(root, name)
                val url = api.sharedAssetUrl(name)
                // Verify against the digest the server publishes next to the
                // asset, when it has one; the length check covers the rest.
                val sha256 = api.sharedAssetSha256(name)
                DownloadFile(url = url, target = target.toOkioPath(), expectedSha256 = sha256) { report ->
                    api.downloadTo(url, target, expectedSha256 = sha256, onProgress = report)
                }
            }
            DownloadManager.enqueue(
                DownloadItem(
                    id = DownloadManager.modelItemId(),
                    kind = DownloadKind.MODEL,
                    title = getString(Res.string.download_model_title),
                    priority = DownloadPriority.HIGH,
                    files = files,
                ),
            ).await()
            refresh()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("GlossoScorer", "scoring pack download failed", e)
            _state.value = _state.value.copy(
                downloading = false,
                present = packPresent(),
                error = getString(Res.string.error_download),
            )
        }
    }

    /**
     * Installs the pack from picked files. A file is accepted when its SHA-256 is
     * the one pinned below (the deployed pack, so this works with no connection)
     * or the one the server publishes now, the digest a download is checked
     * against.
     */
    override fun importPack(files: List<PickedFile>) {
        val current = _state.value
        if (current.downloading || current.importing || files.isEmpty()) return
        _state.value = current.copy(importing = true, error = null)
        scope.launch {
            val error = try {
                when (ModelPackImport.install(root, files, acceptedDigests())) {
                    ModelPackImport.Outcome.COMPLETE -> null
                    ModelPackImport.Outcome.INCOMPLETE -> getString(Res.string.import_model_incomplete)
                    ModelPackImport.Outcome.REJECTED -> getString(Res.string.import_model_rejected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("GlossoScorer", "scoring pack import failed", e)
                getString(Res.string.import_model_failed)
            }
            if (error == null) OfflineModelHint.markSeen(appContext)
            refresh()
            _state.value = _state.value.copy(importing = false, error = error)
        }
    }

    /** Keeps the pack only if every file's digest is pinned or published by the new server. */
    override suspend fun onServerChanged(): Unit = withContext(Dispatchers.IO) {
        if (!packPresent() || _state.value.downloading) return@withContext
        val accepted = acceptedDigests()
        val matches = accepted.all { (name, digests) -> ModelPackImport.sha256(File(root, name)) in digests }
        if (!matches) delete()
    }

    private suspend fun acceptedDigests(): Map<String, Set<String>> =
        PINNED_SHA256.mapValues { (name, pinned) ->
            val served = withTimeoutOrNull(PUBLISHED_DIGEST_TIMEOUT_MS) { appApi.sharedAssetSha256(name) }
            setOfNotNull(pinned, served?.lowercase())
        }

    /** SHA-256 of the pack the app was released with (`<asset>.sha256` on the server). */
    private val PINNED_SHA256 = mapOf(
        PhonemeModelConfig.MODEL_FILE to "f3a768be3f4f1918a0cd52f51798dc50db307167d6b6dd12d0fa8ce6de1e258a",
        PhonemeModelConfig.VOCAB_FILE to "d732ab2456c0c017930001dc9af0b41b3b93d25b2eb9740bf9d925508d7d87d0",
    )

    private const val PUBLISHED_DIGEST_TIMEOUT_MS = 5_000L

    /** Mirrors the shared queue's model item into this scorer's state. */
    internal fun onQueueState(item: DownloadItemState?) {
        if (item == null) return
        val failure = item.failure
        _state.value = _state.value.copy(
            downloading = item.isActive,
            progress = item.progress,
            error = if (failure == null) null else _state.value.error,
        )
        // A failure is terminal: its words (a suspend read) land right after.
        if (failure != null) scope.launch { _state.value = _state.value.copy(error = getString(failure.message)) }
    }

    internal suspend fun onDownloadFinished() {
        refresh()
        _state.value = _state.value.copy(downloading = false, progress = 1f, present = packPresent(), bytes = packBytes())
    }

    internal suspend fun onDownloadFailed(failure: DownloadFailure) {
        _state.value = _state.value.copy(
            downloading = false,
            present = packPresent(),
            error = getString(failure.message),
        )
    }

    /** Removes the pack and unloads the model, freeing the memory it held. */
    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            recognizer?.close()
            recognizer = null
        }
        modelFile().delete()
        vocabFile().delete()
        _state.value = _state.value.copy(
            present = false,
            ready = false,
            bytes = 0,
            progress = 0f,
            error = null,
        )
    }

    /**
     * Loads the model into memory once. Heavy (hundreds of MB, seconds); callers
     * run this off the main thread before the first on-device score. Returns true
     * when the scorer is ready.
     */
    override suspend fun ensureReady(): Boolean {
        if (readyNow()) return true
        if (!available()) return false
        return loadMutex.withLock {
            if (readyNow()) return@withLock true
            _state.value = _state.value.copy(preparing = true, error = null)
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val candidate = Wav2Vec2PhonemeRecognizer(modelFile(), vocabFile())
                    candidate.initialize()
                    candidate
                }
                val ok = loaded.state == ModelState.READY
                if (ok) {
                    recognizer = loaded
                } else {
                    android.util.Log.e(
                        "GlossoScorer",
                        "on-device model not ready: ${loaded.error}",
                    )
                    loaded.close()
                }
                _state.value = _state.value.copy(
                    preparing = false,
                    ready = ok,
                    error = if (ok) null else loaded.error ?: getString(Res.string.error_model_load),
                )
                ok
            } catch (e: CancellationException) {
                // A cancelled load is not a failure: clear the transient state and
                // let the cancellation propagate.
                _state.value = _state.value.copy(preparing = false)
                throw e
            } catch (e: Exception) {
                // A construction or load failure must not leave the UI saying
                // "Preparing..." forever; report it with the app's own copy (never
                // a raw native/English message) and let the caller fall back to
                // cloud scoring. The raw ONNX Runtime error goes to logcat only,
                // so a release failure stays diagnosable without leaking to users.
                android.util.Log.e("GlossoScorer", "on-device model load failed", e)
                _state.value = _state.value.copy(
                    preparing = false,
                    ready = false,
                    error = getString(Res.string.error_model_load),
                )
                false
            }
        }
    }

    /**
     * Scores one attempt with the on-device model, mirroring the cloud path so
     * the result is the same. Must be preceded by [ensureReady]; runs on a
     * worker thread.
     *
     * The first three [error]s are internal invariants, not user copy: callers
     * only ever hit them through a wiring bug. [PracticeEngine] catches any
     * failure from here and shows the localized `error_score`/`error_mic`, so
     * these English developer messages never reach the UI. The no-speech case is
     * different: it is a real learner state and uses a localized string.
     *
     * [referenceAudio] is the content path of the item's reference clip at normal
     * speed. With [BuildConfig.PROSODY_BONUS] on, it is aligned too and the take
     * earns the separate rhythm-and-intonation bonus; the score is unchanged.
     */
    override suspend fun score(
        request: ScoreRequest,
        expectedWordVariants: List<List<String>>,
        referenceAudio: String?,
    ): PronunciationFeedback = withContext(Dispatchers.Default) {
        // Reloads a model released in the background since the caller's ensureReady.
        ensureReady()
        // Held for the whole take, so a release in the background cannot close the
        // session while it is running.
        loadMutex.withLock { scoreLoaded(request, expectedWordVariants, referenceAudio) }
    }

    private suspend fun scoreLoaded(
        request: ScoreRequest,
        expectedWordVariants: List<List<String>>,
        referenceAudio: String?,
    ): PronunciationFeedback {
        val active = recognizer ?: error("On-device scorer is not loaded")
        val expected = request.expectedIpa?.takeIf { it.isNotBlank() }
            ?: error("On-device scoring needs the reference pronunciation")
        val audio = request.audioBase64?.takeIf { it.isNotBlank() }
            ?: error("On-device scoring needs the recorded audio")

        // Nothing usable was heard: report a not-evaluated attempt instead of a
        // fabricated score, so the learner can simply try again.
        val analysis = active.analyze(audio, expected)
            ?: return PronunciationFeedback(evaluated = false)
        val confidences = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
        // The same entry point as `/v1/score`, with the same inputs, so the cloud
        // and the device give the same score with the flag on or off.
        val accentTarget = request.accent?.takeIf { it.isNotBlank() }?.let { AccentTarget(it) }
        val outcome = AccentScoring.score(
            text = request.text,
            canonicalIpa = expected,
            actual = analysis.recognition.ipa,
            language = request.language,
            actualConfidence = confidences,
            wordVariants = expectedWordVariants,
            multiAccent = AccentScoringFlag.enabled,
            accentReferences = request.accentIpa,
            target = accentTarget,
        )
        val result = outcome.result
        val profile = PhoneticProfile.fold(request.profile, result.alignment)
        val stress = StressAnalyzer.analyze(
            timings = analysis.timings.orEmpty(),
            referenceWords = PhoneticComparator.referenceWords(expected),
        )
        return FeedbackAssembler.assemble(
            text = request.text,
            expectedIpa = expected,
            result = result,
            targetLanguage = request.language,
            uiLanguage = request.uiLanguage,
            timings = analysis.timings.orEmpty(),
            profile = profile,
            stress = stress,
            quality = analysis.quality,
            prosody = prosodyBonus(active, analysis, expected, referenceAudio, result.completeness),
        ).copy(accentMatch = outcome.accent?.toModel(accentTarget))
    }

    /** Reference takes already aligned, keyed by clip and reference IPA. */
    private val referenceTakes = object : LinkedHashMap<String, ProsodyBonus.Take>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProsodyBonus.Take>?) =
            size > MAX_REFERENCE_TAKES
    }

    private const val MAX_REFERENCE_TAKES = 8

    /**
     * The rhythm-and-intonation bonus for this take, or null when it is off or
     * cannot be measured. Never fails the score: a reference clip that cannot be
     * fetched, decoded or aligned simply leaves the section out.
     */
    private suspend fun prosodyBonus(
        recognizer: Wav2Vec2PhonemeRecognizer,
        analysis: AudioAnalysis,
        expectedIpa: String,
        referenceAudio: String?,
        completeness: Int,
    ): ProsodyBonusModel? {
        if (!BuildConfig.PROSODY_BONUS || referenceAudio.isNullOrBlank()) return null
        val timings = analysis.timings ?: return null
        return try {
            val reference = referenceTake(recognizer, referenceAudio, expectedIpa) ?: return null
            ProsodyBonus.evaluate(
                reference = reference,
                learner = ProsodyBonus.take(timings, analysis.pcm, analysis.sampleRate),
                completeness = completeness,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("GlossoScorer", "prosody bonus skipped", e)
            null
        }
    }

    private suspend fun referenceTake(
        recognizer: Wav2Vec2PhonemeRecognizer,
        path: String,
        expectedIpa: String,
    ): ProsodyBonus.Take? {
        val key = "$path|$expectedIpa"
        synchronized(referenceTakes) { referenceTakes[key] }?.let { return it }
        val bytes = withContext(Dispatchers.IO) { AssetResolver.localBytes(path) } ?: appApi.assetBytes(path)
        val wav = decodeToWav16k(bytes) ?: return null
        val analysis = recognizer.analyze(Base64.encodeToString(wav, Base64.NO_WRAP), expectedIpa) ?: return null
        val timings = analysis.timings ?: return null
        val take = ProsodyBonus.take(timings, analysis.pcm, analysis.sampleRate)
        synchronized(referenceTakes) { referenceTakes[key] = take }
        return take
    }

    override suspend fun decodeToWav16k(encoded: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) { AudioTranscoder.toWav16k(appContext, encoded) }

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Absolute path of the on-device model file, for the debug model comparison. */
    internal fun modelPath(): File = modelFile()

    /** Absolute path of the on-device vocabulary file, for the debug model comparison. */
    internal fun vocabPath(): File = vocabFile()

    /**
     * Closes and drops the in-memory model without deleting the downloaded pack,
     * so a debug tool that needs the memory for a second model can reclaim it.
     * The next [ensureReady] reloads the pack from disk.
     */
    internal suspend fun unload() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            recognizer?.close()
            recognizer = null
        }
        _state.value = _state.value.copy(ready = false, preparing = false)
    }
}
