package com.glosso.app

import com.glosso.app.api.GlossoApi
import com.glosso.app.api.ScoreRequest
import com.glosso.app.api.downloadToPath
import com.glosso.app.download.DownloadFailure
import com.glosso.app.download.DownloadFile
import com.glosso.app.download.DownloadIds
import com.glosso.app.download.DownloadItem
import com.glosso.app.download.DownloadItemState
import com.glosso.app.download.DownloadKind
import com.glosso.app.download.DownloadPriority
import com.glosso.app.download.Downloader
import com.glosso.app.platform.PickedFile
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.appFiles
import com.glosso.app.platform.logWarning
import com.glosso.app.resources.*
import com.glosso.engine.model.PhonemeModelConfig
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.phonetics.ModelState
import com.glosso.engine.phonetics.Wav2Vec2PhonemeRecognizer
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/**
 * The iOS twin of Android's `OnDeviceScorer`: the model pack lives in
 * `Application Support/scoring` (excluded from the iCloud backup, it can be
 * downloaded again), is downloaded through the app's [Downloader] with the same
 * digest checks, and scores with the C API recognizer through [scoreOnDevice],
 * so a take gets the score the server would give it.
 */
internal object IosOnDeviceScorer : OnDeviceScoring {

    private val fs = FileSystem.SYSTEM
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loadMutex = Mutex()

    private lateinit var root: Path
    private lateinit var downloader: Downloader

    @Volatile
    private var recognizer: Wav2Vec2PhonemeRecognizer? = null

    private val _state = MutableStateFlow(OnDeviceScorerState())
    override val state: StateFlow<OnDeviceScorerState> = _state.asStateFlow()

    fun init(downloader: Downloader) {
        if (::root.isInitialized) return
        this.downloader = downloader
        root = appFiles(PlatformContext).filesDir / "scoring"
        observeMemoryPressure()
        scope.launch { refresh() }
    }

    /**
     * The loaded model is the largest thing the app holds (hundreds of MB). It is
     * released when the app leaves the foreground or iOS warns about memory, as
     * Android releases it on `TRIM_MEMORY_BACKGROUND`, so iOS does not kill the
     * suspended app to get it back; the next take reloads it.
     */
    private fun observeMemoryPressure() {
        val center = NSNotificationCenter.defaultCenter
        listOf(UIApplicationDidEnterBackgroundNotification, UIApplicationDidReceiveMemoryWarningNotification).forEach {
            center.addObserverForName(it, null, null) { _ -> if (recognizer != null) scope.launch { unload() } }
        }
    }

    private fun modelFile() = root / PhonemeModelConfig.MODEL_FILE
    private fun vocabFile() = root / PhonemeModelConfig.VOCAB_FILE
    private fun sizeOf(path: Path) = fs.metadataOrNull(path)?.size ?: 0L

    private fun packPresent() = sizeOf(modelFile()) > 0 && sizeOf(vocabFile()) > 0
    private fun packBytes() = sizeOf(modelFile()) + sizeOf(vocabFile())

    override fun available(): Boolean = ::root.isInitialized && packPresent()

    private fun readyNow(): Boolean = recognizer?.state == ModelState.READY

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(loaded = true, present = packPresent(), ready = readyNow(), bytes = packBytes())
    }

    /** Creates the pack directory, flagged so iCloud and device backups skip it and everything in it. */
    @OptIn(ExperimentalForeignApi::class)
    private fun packDir(): Path {
        fs.createDirectories(root)
        NSURL.fileURLWithPath(root.toString(), isDirectory = true)
            .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        return root
    }

    override suspend fun download() = download(appApi)

    override suspend fun download(api: GlossoApi): Unit = withContext(Dispatchers.IO) {
        if (_state.value.downloading) return@withContext
        // Starting the pack from any surface spends the one-time offline tip, so
        // a later cancel or failure cannot make the offer pop back up.
        OfflineModelHint.markSeen(PlatformContext)
        try {
            val dir = packDir()
            val files = listOf(PhonemeModelConfig.MODEL_FILE, PhonemeModelConfig.VOCAB_FILE).map { name ->
                val target = dir / name
                val url = api.sharedAssetUrl(name)
                val sha256 = api.sharedAssetSha256(name)
                DownloadFile(url = url, target = target, expectedSha256 = sha256) { report ->
                    api.downloadToPath(url, target, expectedSha256 = sha256, onProgress = report)
                }
            }
            downloader.enqueue(
                DownloadItem(
                    id = DownloadIds.MODEL,
                    kind = DownloadKind.MODEL,
                    title = getString(Res.string.download_model_title),
                    priority = DownloadPriority.HIGH,
                    files = files,
                ),
            ).await()
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning(TAG, "scoring pack download failed", e)
            _state.value = _state.value.copy(
                downloading = false,
                present = packPresent(),
                error = getString(Res.string.error_download),
            )
        }
    }

    override fun importPack(files: List<PickedFile>) {
        val current = _state.value
        if (current.downloading || current.importing || files.isEmpty()) return
        _state.value = current.copy(importing = true, error = null)
        scope.launch {
            val error = try {
                when (ModelPackImport.install(fs, packDir(), files, ModelPackImport.acceptedDigests(appApi))) {
                    ModelPackImport.Outcome.COMPLETE -> null
                    ModelPackImport.Outcome.INCOMPLETE -> getString(Res.string.import_model_incomplete)
                    ModelPackImport.Outcome.REJECTED -> getString(Res.string.import_model_rejected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning(TAG, "scoring pack import failed", e)
                getString(Res.string.import_model_failed)
            }
            if (error == null) OfflineModelHint.markSeen(PlatformContext)
            refresh()
            _state.value = _state.value.copy(importing = false, error = error)
        }
    }

    /** Keeps the pack only if every file's digest is pinned or published by the new server. */
    override suspend fun onServerChanged(): Unit = withContext(Dispatchers.IO) {
        if (!packPresent() || _state.value.downloading) return@withContext
        val accepted = ModelPackImport.acceptedDigests(appApi)
        val matches = accepted.all { (name, digests) -> ModelPackImport.sha256(fs, root / name) in digests }
        if (!matches) delete()
    }

    /** Mirrors the shared queue's model item into this scorer's state. */
    fun onQueueState(item: DownloadItemState?) {
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

    suspend fun onDownloadFinished() {
        refresh()
        _state.value = _state.value.copy(downloading = false, progress = 1f, present = packPresent(), bytes = packBytes())
    }

    suspend fun onDownloadFailed(failure: DownloadFailure) {
        _state.value = _state.value.copy(downloading = false, present = packPresent(), error = getString(failure.message))
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            recognizer?.close()
            recognizer = null
        }
        fs.delete(modelFile())
        fs.delete(vocabFile())
        _state.value = _state.value.copy(present = false, ready = false, bytes = 0, progress = 0f, error = null)
    }

    override suspend fun ensureReady(): Boolean {
        if (readyNow()) return true
        if (!available()) return false
        return loadMutex.withLock {
            if (readyNow()) return@withLock true
            _state.value = _state.value.copy(preparing = true, error = null)
            try {
                val loaded = withContext(Dispatchers.IO) {
                    Wav2Vec2PhonemeRecognizer(modelFile().toString(), vocabFile().toString()).also { it.initialize() }
                }
                val ok = loaded.state == ModelState.READY
                if (ok) {
                    recognizer = loaded
                } else {
                    logWarning(TAG, "on-device model not ready: ${loaded.error}")
                    loaded.close()
                }
                _state.value = _state.value.copy(
                    preparing = false,
                    ready = ok,
                    // The raw ONNX Runtime message stays in the log; the learner gets the app's copy.
                    error = if (ok) null else getString(Res.string.error_model_load),
                )
                ok
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(preparing = false)
                throw e
            } catch (e: Exception) {
                logWarning(TAG, "on-device model load failed", e)
                _state.value = _state.value.copy(preparing = false, ready = false, error = getString(Res.string.error_model_load))
                false
            }
        }
    }

    /**
     * Scores one take like `/v1/score`. There is no prosody bonus: it is off by
     * default on Android too (`BuildConfig.PROSODY_BONUS`) and needs the
     * reference clip decoded to 16 kHz, which [decodeToWav16k] does not do here.
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
        loadMutex.withLock {
            val active = recognizer ?: error("On-device scorer is not loaded")
            scoreOnDevice(active, request, expectedWordVariants)
        }
    }

    // shortcut: the debug-only native take (score a reference voice as a take) is Android-only;
    // decode Ogg Vorbis to 16 kHz here if iOS ever gets the debug tools.
    override suspend fun decodeToWav16k(encoded: ByteArray): ByteArray? = null

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private suspend fun unload() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            recognizer?.close()
            recognizer = null
        }
        _state.value = _state.value.copy(ready = false, preparing = false)
    }

    private const val TAG = "GlossoScorer"
}
