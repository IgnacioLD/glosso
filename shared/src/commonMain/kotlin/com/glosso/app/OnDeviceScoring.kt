package com.glosso.app

import com.glosso.app.api.GlossoApi
import com.glosso.app.api.ScoreRequest
import com.glosso.app.platform.PickedFile
import com.glosso.engine.accent.AccentScoring
import com.glosso.engine.accent.AccentTarget
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.ProsodyBonusModel
import com.glosso.engine.phonetics.AudioAnalysis
import com.glosso.engine.phonetics.FeedbackAssembler
import com.glosso.engine.phonetics.PhonemeRecognizer
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.PhoneticProfile
import com.glosso.engine.phonetics.StressAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State of the optional on-device pronunciation scorer: whether its model pack
 * is on the device, is being downloaded, or is loaded in memory.
 */
data class OnDeviceScorerState(
    val loaded: Boolean = false,
    val present: Boolean = false,
    val bytes: Long = 0,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    /** True while the model is being loaded into memory (heavy, once). */
    val preparing: Boolean = false,
    /** True while picked files are copied and verified ([OnDeviceScoring.importPack]). */
    val importing: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
)

/**
 * The optional on-device scorer as the app sees it: a downloaded model pack
 * scored by the same engine the cloud runs. The pronunciation loop scores with
 * it; the offline screens show its [state] and install or remove it. Android
 * (`OnDeviceScorer`) and iOS (`IosOnDeviceScorer`) implement it with their ONNX
 * recognizer and score through [scoreOnDevice].
 */
interface OnDeviceScoring {
    /**
     * False where this platform has no on-device scorer yet ([NoOnDeviceScoring]):
     * the screens then never offer the model pack, whose download would do nothing.
     */
    val supported: Boolean get() = true

    /** Whether the pack is on the device, downloading or loaded, for the UI. */
    val state: StateFlow<OnDeviceScorerState>

    /** True when the model pack is on the device, loaded or not. */
    fun available(): Boolean

    /** Loads the model once (heavy); true when it can score. */
    suspend fun ensureReady(): Boolean

    /** Scores one take like `/v1/score` would; call after [ensureReady]. */
    suspend fun score(
        request: ScoreRequest,
        expectedWordVariants: List<List<String>> = emptyList(),
        referenceAudio: String? = null,
    ): PronunciationFeedback

    /** Downloads the model pack from [api]'s host. */
    suspend fun download(api: GlossoApi)

    /** Downloads the model pack from the app's own host. */
    suspend fun download()

    /**
     * Installs the pack from files the learner picked instead of downloading it,
     * verified against the same digests a download is. Runs in the background;
     * the outcome shows in [state] (`present`, or a localized `error`).
     */
    fun importPack(files: List<PickedFile>) = Unit

    /** Removes the pack and unloads the model, freeing the memory it held. */
    suspend fun delete()

    /** Clears the last error once the learner has seen it. */
    fun dismissError()

    /**
     * The app now talks to another server ([ServerSwitch]). The installed pack is
     * kept only if its digest is one the new server accepts, so on-device scores
     * keep matching that server's.
     */
    suspend fun onServerChanged() = Unit

    /**
     * Decodes a reference clip (the content's compressed audio) to the 16 kHz
     * mono WAV the recognizer reads, or null when it cannot. Used by the
     * debug-only native take, which scores another reference voice as a take.
     */
    suspend fun decodeToWav16k(encoded: ByteArray): ByteArray?

    companion object {
        /** Approximate download size of the pack, shown before it exists. */
        const val APPROX_BYTES = 358_357_774L
    }
}

/**
 * Scores one take with a loaded [recognizer], mirroring the cloud path so the
 * result is the same; both platforms' scorers run it. [prosody] is the optional
 * rhythm-and-intonation bonus (null leaves the section out).
 *
 * The first three [error]s are internal invariants, not user copy: callers only
 * ever hit them through a wiring bug. [PracticeEngine] catches any failure from
 * here and shows the localized `error_score`/`error_mic`, so these English
 * developer messages never reach the UI.
 */
suspend fun scoreOnDevice(
    recognizer: PhonemeRecognizer,
    request: ScoreRequest,
    expectedWordVariants: List<List<String>>,
    prosody: suspend (analysis: AudioAnalysis, expectedIpa: String, completeness: Int) -> ProsodyBonusModel? =
        { _, _, _ -> null },
): PronunciationFeedback {
    val expected = request.expectedIpa?.takeIf { it.isNotBlank() }
        ?: error("On-device scoring needs the reference pronunciation")
    val audio = request.audioBase64?.takeIf { it.isNotBlank() }
        ?: error("On-device scoring needs the recorded audio")

    // Nothing usable was heard: report a not-evaluated attempt instead of a
    // fabricated score, so the learner can simply try again.
    val analysis = recognizer.analyze(audio, expected)
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
        prosody = prosody(analysis, expected, result.completeness),
    ).copy(accentMatch = outcome.accent?.toModel(accentTarget))
}

/**
 * No model pack on this platform yet: every take is scored in the cloud and the
 * offline screens never offer the download as installed.
 */
object NoOnDeviceScoring : OnDeviceScoring {
    override val supported: Boolean = false

    private val _state = MutableStateFlow(OnDeviceScorerState(loaded = true))
    override val state: StateFlow<OnDeviceScorerState> = _state.asStateFlow()

    override fun available(): Boolean = false
    override suspend fun ensureReady(): Boolean = false
    override suspend fun score(
        request: ScoreRequest,
        expectedWordVariants: List<List<String>>,
        referenceAudio: String?,
    ): PronunciationFeedback = error("No on-device scorer on this platform")

    override suspend fun download(api: GlossoApi) = Unit
    override suspend fun download() = Unit
    override suspend fun delete() = Unit
    override fun dismissError() = Unit
    override suspend fun decodeToWav16k(encoded: ByteArray): ByteArray? = null
}
