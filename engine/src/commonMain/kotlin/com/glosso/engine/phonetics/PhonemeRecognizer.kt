package com.glosso.engine.phonetics

import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.PronunciationQualityModel

/** Lifecycle state of the phoneme recognition model. */
enum class ModelState { UNINITIALIZED, LOADING, READY, FAILED }

/** One phone the model heard, with how sure it was (0..1). */
data class RecognizedPhone(
    val phone: String,
    val confidence: Float,
)

/**
 * A complete recognition result: the recognized IPA plus the tokens the model
 * emitted, each with how sure it was.
 *
 * [phones] are the model's own tokens, which do not map one-to-one onto the
 * phones the scorer parses out of [ipa] (a diphthong can be a single token).
 * Use [PhoneticComparator.phoneConfidencesOf] to expand them into one confidence
 * per scored phone. Callers that cannot measure confidence leave it empty, so
 * the scorer trusts every phone.
 */
data class Recognition(
    val ipa: String,
    val phones: List<RecognizedPhone> = emptyList(),
)

/**
 * Result of analysing one recording in a single model pass: what was heard, plus
 * where each reference phone was spoken when a reference is available.
 */
data class AudioAnalysis(
    val recognition: Recognition,
    val timings: List<PhoneTimingModel>? = null,
    /** Silence-trimmed mono PCM the model analysed; the cheap scorer reuses it. */
    val pcm: FloatArray? = null,
    val sampleRate: Int = 0,
    /** Additive shared-inventory quality decision, when a reference was aligned. */
    val quality: PronunciationQualityModel? = null,
)

/**
 * The recognizer contract: audio in, IPA phoneme string out.
 *
 * Kept deliberately small and replaceable so the wav2vec2 ONNX implementation can
 * be swapped for a different acoustic model (or a future per-language model)
 * without touching the scoring layer.
 */
interface PhonemeRecognizer {
    /** Returns the recognized IPA for a base64 WAV, or null when nothing usable was heard. */
    fun recognize(base64Wav: String): String?

    /**
     * Confidence-aware variant of [recognize]. The default declares no
     * confidence, so older or alternative recognizers keep working and the
     * scorer treats every phone as reliable.
     */
    fun recognizeDetailed(base64Wav: String): Recognition? =
        recognize(base64Wav)?.let { Recognition(it) }

    /**
     * Recognizes the audio and, when [referenceIpa] is given, forced-aligns that
     * reference onto it to obtain per-phone timing, energy and pitch. One model
     * pass produces both results. Returns null when nothing usable was heard, or
     * [AudioAnalysis] with `timings == null` when alignment is not possible (an
     * unrecognised reference phone), so callers still get the score.
     */
    fun analyze(base64Wav: String, referenceIpa: String? = null): AudioAnalysis? =
        recognizeDetailed(base64Wav)?.let { AudioAnalysis(it) }

    /** Loads the model. Heavy; must run off the main thread. Safe to call repeatedly. */
    fun initialize()

    val state: ModelState

    val error: String?
}
