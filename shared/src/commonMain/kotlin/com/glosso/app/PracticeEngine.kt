package com.glosso.app

import com.glosso.app.api.GlossoApi
import com.glosso.app.api.ScoreRequest
import com.glosso.app.audio.AudioRecorder
import com.glosso.app.audio.AudioSource
import com.glosso.app.audio.Player
import com.glosso.app.audio.clipFor
import com.glosso.app.audio.platformRecorder
import com.glosso.app.platform.AppFiles
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.PlatformInfo
import com.glosso.app.platform.appFiles
import com.glosso.app.platform.platformInfo
import com.glosso.app.resources.*
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.PronunciationFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import kotlinx.io.IOException
import org.jetbrains.compose.resources.getString

/** Everything about one spoken attempt: the recorder, the cloud score and the
 *  reference and own-audio playback. */
data class AttemptState(
    val recording: Boolean = false,
    val scoring: Boolean = false,
    val result: PronunciationFeedback? = null,
    /**
     * True when nothing usable was heard. The UI says so and offers a retry; the
     * attempt is never counted as a failure (no score recorded, no profile fold).
     */
    val notEvaluated: Boolean = false,
    val takePath: String? = null,
    val profile: PhoneticProfileModel? = null,
    val error: String? = null,
    /**
     * True when the take could not be scored because there is no connection and
     * the on-device model is not installed. The UI offers to download it instead
     * of leaving the learner at an error with nothing to do.
     */
    val offlineNoModel: Boolean = false,
) {
    val hasTake: Boolean get() = takePath != null

    /**
     * True once there is something to move on from: a recorded take, or a score.
     * The `result != null` arm is what lets the debug take injector (which has no
     * audio file) still drive the onboarding, practice and drill flows; a real
     * recording always sets [takePath], so [hasTake] alone would cover release.
     */
    val hasOutcome: Boolean get() = hasTake || result != null || notEvaluated
}

/**
 * The pronunciation loop, shared by free practice and the lesson player so both
 * record, score, play a take back and choose a voice the same way. It owns no
 * screen or list state: callers keep their own flow and hand in the text to say.
 */
class PracticeEngine(
    private val api: GlossoApi,
    private val scope: CoroutineScope,
    private val appContext: PlatformContext,
    private val profiles: ProfileStore,
    /** The optional model pack; the cloud scores when it is absent. */
    private val onDevice: OnDeviceScoring,
    private val recorder: AudioRecorder = platformRecorder(),
    /** What a scored take is in the usage statistics (`exercise_completed.kind`). */
    private val exerciseKind: String = "practice",
    /** The `screen_view` name of the screen this loop runs in, for `app_error`. */
    private val screen: String = "practice",
    /** Enables the debug-only take injection (`BuildConfig.DEBUG` on Android). Off in release. */
    private val debugTools: Boolean = false,
) {

    /** Whether the last take was scored by the on-device model, not the cloud. */
    @Volatile
    private var scoredOnDevice = false

    /** Shared so a screen can also chain a piece (paragraph after paragraph). */
    val player = Player()

    private val files: AppFiles = appFiles(appContext)
    private val platform: PlatformInfo = platformInfo(appContext)

    /** The latest take, in the app's cache dir (as `cacheDir/take.wav` always was). */
    private val takeFile = files.cacheDir / "take.wav"

    private val _playing = MutableStateFlow(false)

    /** True while reference audio or a take is playing, for the UI indicator. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    init {
        player.onPlayingChanged = { _playing.value = it }
    }

    private val _state = MutableStateFlow(AttemptState(profile = profiles.current))
    val state: StateFlow<AttemptState> = _state.asStateFlow()

    /** Live microphone level, 0..1, while recording. Drives the waveform. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private var levelJob: kotlinx.coroutines.Job? = null

    /**
     * The score request in flight, if any. Cancelled when the caller moves on, so
     * a skipped or abandoned attempt cannot land its result on the next item.
     */
    private var scoreJob: kotlinx.coroutines.Job? = null

    /** Running phonetic profile after the latest attempt, for drill selection. */
    val profile: PhoneticProfileModel? get() = profiles.current

    /** Called after each scored attempt so callers can track session progress. */
    var onScored: ((PronunciationFeedback) -> Unit)? = null

    /**
     * Whether a scored take says something about the learner's sounds. A phrase
     * said from memory that turned out to be another phrase does not: every
     * expected sound would count as a miss, so it is kept out of the profile.
     */
    var judgesSounds: (PronunciationFeedback) -> Boolean = { true }

    fun play(url: String) = player.play(url)

    /** Plays a resolved source: a downloaded reference file or the remote URL. */
    fun play(source: AudioSource, speed: Float = 1f) = player.play(source, speed = speed)

    /** Plays a downloaded reference file, used when the lesson is offline. */
    fun playFile(path: String) = player.playFile(path)

    fun playTake() {
        val path = _state.value.takePath ?: return
        player.playFile(path)
        UsageEvents.audioPlayed("take")
    }

    /**
     * Starts recording, or stops and scores the take. [referenceAudio] is the
     * item's reference clip at normal speed (see [referenceAudioPath]), used only
     * for the optional rhythm-and-intonation bonus. [accentIpa] is the item's
     * published references per accent, which the scorer accepts next to
     * [expectedIpa] when multi-accent scoring is on.
     */
    fun toggle(
        text: String,
        expectedIpa: String? = null,
        expectedVariants: List<List<String>> = emptyList(),
        referenceAudio: String? = null,
        accentIpa: Map<String, String> = emptyMap(),
    ) {
        if (_state.value.scoring) return
        if (_state.value.recording) {
            stopAndScore(text, expectedIpa, expectedVariants, referenceAudio, accentIpa)
        } else {
            start()
        }
    }

    private fun start() {
        runCatching { recorder.start() }
            .onSuccess {
                _state.value = _state.value.copy(
                    recording = true,
                    result = null,
                    notEvaluated = false,
                    error = null,
                    offlineNoModel = false,
                )
                startLevelLoop()
            }
            .onFailure { e ->
                // Resources load through a suspend call; on the main dispatcher it
                // completes before the next frame, as the direct read did.
                scope.launch { _state.value = _state.value.copy(error = getString(Res.string.error_mic)) }
                UsageEvents.appError("mic_failed", screen, e)
            }
    }

    private fun startLevelLoop() {
        levelJob?.cancel()
        levelJob = scope.launch {
            while (true) {
                _level.value = recorder.amplitude
                kotlinx.coroutines.delay(50)
            }
        }
    }

    private fun stopLevelLoop() {
        levelJob?.cancel()
        levelJob = null
        _level.value = 0f
    }

    private fun stopAndScore(
        text: String,
        expectedIpa: String?,
        expectedVariants: List<List<String>>,
        referenceAudio: String?,
        accentIpa: Map<String, String>,
    ) {
        stopLevelLoop()
        val wav = runCatching { recorder.stop() }.getOrElse { e ->
            _state.value = _state.value.copy(recording = false)
            scope.launch { _state.value = _state.value.copy(error = getString(Res.string.error_mic)) }
            UsageEvents.appError("mic_failed", screen, e)
            return
        }
        scoreTake(wav, text, expectedIpa, expectedVariants, referenceAudio, accentIpa)
    }

    /**
     * Debug-only: scores a ready WAV (16 kHz mono) exactly like a recorded take,
     * so the on-device path, including the rhythm-and-intonation bonus, can be
     * exercised on an emulator with no microphone. Inert in release builds.
     */
    fun debugScoreWav(
        wav: ByteArray,
        text: String,
        expectedIpa: String?,
        expectedVariants: List<List<String>>,
        referenceAudio: String?,
        accentIpa: Map<String, String> = emptyMap(),
    ) {
        if (!debugTools || _state.value.recording || _state.value.scoring) return
        scoreTake(wav, text, expectedIpa, expectedVariants, referenceAudio, accentIpa)
    }

    private fun scoreTake(
        wav: ByteArray,
        text: String,
        expectedIpa: String?,
        expectedVariants: List<List<String>>,
        referenceAudio: String?,
        accentIpa: Map<String, String>,
    ) {
        _state.value = _state.value.copy(recording = false, scoring = true, error = null)
        val saved = runCatching { files.fileSystem.write(takeFile) { write(wav) }; takeFile.toString() }.getOrNull()
        val audioBase64 = Base64.encode(wav)
        scoreJob?.cancel()
        val requestedAt = TimeSource.Monotonic.markNow()
        scoreJob = scope.launch {
            profiles.await()
            // Read and write the profile under one lock, so two attempts in flight
            // do not both start from the same base and lose the other's fold.
            runCatching {
                profiles.locked {
                    val request = ScoreRequest(
                        text = text,
                        // The content already carries the reference IPA (CMUdict for
                        // English, espeak for other languages), so the server does not
                        // need a runtime G2P for the target language.
                        expectedIpa = expectedIpa?.takeIf { it.isNotBlank() },
                        audioBase64 = audioBase64,
                        language = LanguagePreferences.current.code,
                        uiLanguage = platform.uiLanguage,
                        profile = profiles.current,
                        accent = VoicePreferences.accent,
                        accentIpa = accentIpa,
                    )
                    val feedback = score(request, expectedVariants, referenceAudio)
                    if (feedback.evaluated && judgesSounds(feedback)) {
                        // Persist the folded aggregate and append this attempt's
                        // dated per-sound records, so the profile gains a real
                        // history.
                        feedback.profile?.let { profiles.record(it, feedback.alignment) }
                        // Captures this week's baseline the first time the week is
                        // seen, so the progress screen can show a real weekly delta.
                        PhonemeHistory.observe(profiles.current)
                    }
                    feedback
                }
            }
                .onSuccess { feedback ->
                    if (!feedback.evaluated) {
                        // Nothing usable was heard: say so, keep the take so the
                        // learner can listen, and record nothing. No score, no
                        // streak, no word marked failed - a free retry.
                        _state.value = _state.value.copy(
                            scoring = false,
                            result = null,
                            notEvaluated = true,
                            takePath = saved,
                        )
                        UsageEvents.appError("not_evaluated", screen)
                        return@onSuccess
                    }
                    _state.value = _state.value.copy(
                        scoring = false,
                        result = feedback,
                        notEvaluated = false,
                        profile = profiles.current,
                        takePath = saved,
                    )
                    trackTake(feedback, latencyMs = requestedAt.elapsedNow().inWholeMilliseconds)
                    onScored?.invoke(feedback)
                }
                .onFailure { e ->
                    // A cancelled attempt is not an error: the caller moved on and
                    // cancelled this job. Rethrow so it never surfaces as a score
                    // failure on the next item.
                    if (e is CancellationException) throw e
                    // The score is a cloud call. A dead connection reads as an
                    // IOException: say so plainly instead of leaking a stack trace.
                    val offline = e is IOException
                    // Offline with no on-device pack: the take is not scored and
                    // cannot be, but the learner can install the pack and try
                    // again, so the UI is told to offer it.
                    val needsModel = offline && onDevice.supported && onDeviceLanguage() && !onDevice.available()
                    UsageEvents.appError(if (offline) "score_offline" else "score_failed", screen, e)
                    _state.value = _state.value.copy(
                        scoring = false,
                        offlineNoModel = needsModel,
                        // The offer card says why and what to do; an extra error
                        // line under the recorder would only repeat it.
                        error = if (needsModel) {
                            null
                        } else {
                            getString(
                                if (offline) Res.string.error_score_offline else Res.string.error_score,
                            )
                        },
                    )
                }
        }
    }

    /** Whether the downloadable model pack can score the language being learned. */
    private fun onDeviceLanguage(): Boolean = LanguagePreferences.current.onDeviceScoring

    /**
     * Scores with the optional on-device model when its pack is installed, so a
     * recording is scored with no network; otherwise the cloud endpoint. If the
     * pack is present but cannot load, it falls back to the cloud rather than
     * failing the attempt.
     *
     * The cloud widens the reference with its own CMUdict; the device cannot, so
     * it is handed the same accepted readings the content carries. Both paths then
     * score against the same set and return the same verdict.
     */
    private suspend fun score(
        request: ScoreRequest,
        expectedVariants: List<List<String>>,
        referenceAudio: String? = null,
    ): PronunciationFeedback =
        if (!onDeviceLanguage() || !onDevice.available() || request.expectedIpa.isNullOrBlank()) {
            // A language the pack cannot score, no pack, or no reference
            // pronunciation for this item (on-device scoring needs it): let the
            // cloud phonemize and score.
            scoredOnDevice = false
            api.score(request)
        } else {
            val loaded = try {
                onDevice.ensureReady()
            } catch (e: CancellationException) {
                // A cancelled attempt must abort, not fall through to the cloud.
                throw e
            } catch (_: Exception) {
                // A pack that cannot load falls back to the cloud, never fails.
                false
            }
            scoredOnDevice = loaded
            if (loaded) onDevice.score(request, expectedVariants, referenceAudio) else api.score(request)
        }

    /**
     * One judged take in the usage statistics. A cloud take is already counted by
     * the server (`score_attempt`); an on-device one never reaches it, so it is
     * reported here too, with the same rule ids and only the score band.
     */
    private fun trackTake(feedback: PronunciationFeedback, latencyMs: Long) {
        val band = scoreBand(feedback.score)
        Analytics.track(
            "exercise_completed",
            mapOf(
                "kind" to exerciseKind,
                "score_band" to band,
                "mastery" to feedback.isMastery,
                "latency_ms" to latencyMs.coerceIn(0, MAX_LATENCY_MS),
                "on_device" to scoredOnDevice,
            ),
        )
        if (scoredOnDevice) {
            Analytics.track(
                "pronunciation_attempt",
                mapOf(
                    "mode" to exerciseKind,
                    "score_band" to band,
                    "mastery" to feedback.isMastery,
                    "errors" to feedback.errors.mapNotNull { it.ruleId }.distinct().take(20).joinToString(","),
                ),
            )
        }
    }

    /**
     * Drops the last result and take, keeping the profile, for the next utterance.
     * A recording in flight is stopped first: advancing, skipping or leaving the
     * screen must never keep the microphone open or leave the recorder UI stuck on
     * "Recording" for the next item.
     */
    fun clearResult() {
        if (_state.value.recording) {
            stopLevelLoop()
            runCatching { recorder.stop() }
        }
        // A score still in flight belongs to the attempt being abandoned: cancel
        // it, so it cannot surface on the next item or be added to the session.
        scoreJob?.cancel()
        scoreJob = null
        _state.value = _state.value.copy(
            recording = false,
            scoring = false,
            result = null,
            notEvaluated = false,
            takePath = null,
            offlineNoModel = false,
        )
        // The take is the learner's own voice in a temp file: drop it when the
        // attempt is abandoned so it does not linger in the cache.
        runCatching { files.fileSystem.delete(takeFile) }
    }

    /**
     * Downloads the optional on-device scoring pack, so an offline take can be
     * scored on this device. Triggered from the offer card shown after a failed
     * offline score.
     */
    fun downloadOnDeviceModel() {
        scope.launch { onDevice.download(api) }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Debug-only: scores a take given as text plus the IPA it should produce, with
     * no microphone and no on-device model, so the whole visible loop can be
     * exercised on an emulator that has neither. Inert in release builds.
     */
    fun debugInjectTake(
        text: String,
        expectedIpa: String?,
        actualIpa: String,
        accentIpa: Map<String, String> = emptyMap(),
    ) {
        if (!debugTools || _state.value.scoring) return
        _state.value = _state.value.copy(recording = false, scoring = true, result = null, error = null)
        scoreJob?.cancel()
        scoreJob = scope.launch {
            profiles.await()
            runCatching {
                profiles.locked {
                    val request = ScoreRequest(
                        text = text,
                        expectedIpa = expectedIpa?.takeIf { it.isNotBlank() },
                        actualIpa = actualIpa,
                        language = LanguagePreferences.current.code,
                        uiLanguage = platform.uiLanguage,
                        profile = profiles.current,
                        accent = VoicePreferences.accent,
                        accentIpa = accentIpa,
                    )
                    val feedback = api.score(request)
                    if (judgesSounds(feedback)) {
                        feedback.profile?.let { profiles.record(it, feedback.alignment) }
                        PhonemeHistory.observe(profiles.current)
                    }
                    feedback
                }
            }
                .onSuccess { feedback ->
                    _state.value = _state.value.copy(
                        scoring = false,
                        result = feedback,
                        profile = profiles.current,
                        takePath = null,
                    )
                    onScored?.invoke(feedback)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    val offline = e is IOException
                    val needsModel = offline && onDevice.supported && onDeviceLanguage() && !onDevice.available()
                    _state.value = _state.value.copy(
                        scoring = false,
                        offlineNoModel = needsModel,
                        error = if (needsModel) {
                            null
                        } else {
                            getString(
                                if (offline) Res.string.error_score_offline else Res.string.error_score,
                            )
                        },
                    )
                }
        }
    }

    fun release() {
        stopLevelLoop()
        scoreJob?.cancel()
        scoreJob = null
        player.stop()
        runCatching { recorder.stop() }
        // No take is needed once the engine is gone: remove the temp recording.
        runCatching { files.fileSystem.delete(takeFile) }
        // The recorder is gone: the state must not claim it is still listening.
        _state.value = _state.value.copy(recording = false, scoring = false)
    }
}

/** The catalogue's ceiling for `exercise_completed.latency_ms`. */
private const val MAX_LATENCY_MS = 120_000L

/**
 * The reference clip the learner hears at normal speed: the item's one voice in
 * the learner's accent, or any normal-speed voice the item has. Slow clips are never the reference for
 * rhythm, they stretch it on purpose.
 */
fun referenceAudioPath(audio: Map<String, String>): String? =
    audio.clipFor(VoicePreferences.accent)
