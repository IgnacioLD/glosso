package com.glosso.engine.model

import kotlinx.serialization.Serializable

@Serializable
data class WordOffset(
    val w: String, // word text
    val s: Float,  // start seconds
    val e: Float   // end seconds
)

@Serializable
data class Sentence(
    val id: Int? = null,
    val text: String,
    /** IPA - word-separated canonical phonemization of [text]. */
    val ipa: String,
    val level: String,
    val topic: String,
    val language: String,
    val audio1: String? = null,
    val audio2: String? = null,
    val wordOffsets: List<WordOffset> = emptyList(),
    val translations: Map<String, String> = emptyMap(),
) {
    fun translationFor(uiLanguage: String): String? = translations[uiLanguage]
}

@Serializable
data class PronunciationFeedback(
    /**
     * False when the recording held no usable speech and nothing was scored. The
     * learner is told the attempt could not be evaluated and must not be punished
     * for it: no 0/100, no broken streak, no word marked as failed.
     */
    val evaluated: Boolean = true,
    val score: Int = 0,
    val transcription: String = "",
    val feedback: String? = null,
    val normalizedActual: String? = null,
    val normalizedExpected: String? = null,
    val alignment: List<PhonemeMatchModel> = emptyList(),
    val letterFeedback: List<LetterFeedbackModel> = emptyList(),
    val pairHints: List<PairHint> = emptyList(),
    /** Quality of the sounds actually produced, independent of anything skipped. */
    val accuracy: Int = 0,
    /** Percent of the reference sounds the learner actually produced. */
    val completeness: Int = 0,
    /** Per-word results in sentence order; the weakest word drives the coaching. */
    val words: List<WordFeedbackModel> = emptyList(),
    /** Forced-alignment result: where each reference phone was actually spoken. */
    val timings: List<PhoneTimingModel> = emptyList(),
    /** Diagnosed sound errors with a suggested drill, worst first. */
    val errors: List<PhonemeErrorModel> = emptyList(),
    /** Measured stress/rhythm of the attempt, when a reference stress was known. */
    val stress: StressReportModel? = null,
    /** Running phonetic profile after folding this attempt in, when one was sent. */
    val profile: PhoneticProfileModel? = null,
    /** The full mastery decision (score + completeness + no destroyed word). */
    val isMastery: Boolean = false,
    /** The named result shown to the learner, replacing the raw percentage. */
    val level: MasteryLevel = MasteryLevel.NOT_YET,
    /**
     * Reference-free accent/fluency measured from prosody and rhythm. A separate
     * signal that complements the phoneme score; null when there was no audio.
     */
    val fluency: FluencyModel? = null,
    /** Per-phone explanatory acoustics (formants, fricative moments), when audio was scored. */
    val acoustics: List<PhoneAcousticsModel> = emptyList(),
    /**
     * Shared-inventory quality decision: a separate, additive signal with its own
     * per-phone breakdown and worst-3. Null when no audio was scored or the
     * reference could not be aligned.
     */
    val quality: PronunciationQualityModel? = null,
    /**
     * Extra points for rhythm and intonation against the reference audio. Shown
     * next to [score], never added into it. Null when the bonus is off, no
     * reference audio was aligned, or it does not apply to this take.
     */
    val prosody: ProsodyBonusModel? = null,
    /**
     * The accent system the take matched best, when multi-accent scoring is on
     * (`GLOSSO_MULTI_ACCENT`). Null in the default single-reference scoring.
     */
    val accentMatch: AccentMatchModel? = null,
)

/**
 * Which accent system a take was scored against: the whole system that matched
 * best, never a mix of accents (docs/MULTI-ACCENT.md). [target] is the accent the
 * learner is mastering; it only breaks exact ties.
 */
@Serializable
data class AccentMatchModel(
    /** "<accent>-<gender>", e.g. "en-US-South-female". */
    val system: String,
    val accent: String,
    val label: String,
    val gender: VoiceGender,
    val score: Int,
    val target: String? = null,
    /** False while the accent's phonology has not been measured with its own native audio. */
    val calibrated: Boolean = false,
    /** Every system's score for this take, best first. */
    val candidates: List<AccentCandidateModel> = emptyList(),
)

@Serializable
data class AccentCandidateModel(val system: String, val accent: String, val score: Int)

/**
 * "Ritmo y entonacion": extra points for following the reference's rhythm,
 * pauses, stress and intonation (see `ProsodyBonus`).
 *
 * [points] is 0..[maxPoints] and is purely additive: it is never folded into
 * [PronunciationFeedback.score] and never lowers anything. The components are
 * each 0..1, 1 = same shape as the reference; [pauses] and [intonation] are null
 * when they could not be measured.
 */
@Serializable
data class ProsodyBonusModel(
    val points: Int,
    val maxPoints: Int,
    /** Weighted overall shape the points are derived from, 0..1. */
    val shape: Float,
    val rhythm: Float,
    val tempo: Float,
    val pauses: Float? = null,
    val stress: Float,
    val intonation: Float? = null,
    /** Learner speaking time / reference speaking time (1 = same pace). */
    val tempoRatio: Float,
    /** Pause time beyond the reference's, in ms. */
    val extraPauseMs: Int? = null,
)

/**
 * The reference-free accent/fluency signal. It never changes [PronunciationFeedback.score]:
 * it is a complement measured from cheap DSP (pitch contour and rhythm) that the
 * acoustic model does not see.
 *
 * [accentScore] is a prior-signed z-score average against fixed native anchors:
 * higher means further from native rhythm/intonation ("more accented").
 */
@Serializable
data class FluencyModel(
    val accentScore: Float,
    val speechRateSylPerS: Float? = null,
    val pauseRatio: Float? = null,
    val longestPauseSeconds: Float? = null,
    val f0StdSemitones: Float? = null,
    val f0RangeSemitones: Float? = null,
    val npvi: Float? = null,
    val findings: List<AcousticFindingModel> = emptyList(),
)

/**
 * One objective acoustic measurement behind a per-phone explanation. [code] is a
 * stable id (e.g. "fricative.theta.sibilant") the UI can localize; [message] is a
 * compact English fallback carrying the measured and target values.
 */
@Serializable
data class AcousticFindingModel(
    val code: String,
    /** Reference phone the finding is about, or "-" for a global finding. */
    val phoneme: String,
    val measured: Float? = null,
    val target: Float? = null,
    val message: String? = null,
)

/** Objective formant / fricative measurements for one reference phone. */
@Serializable
data class PhoneAcousticsModel(
    val phoneme: String,
    val f1: Float? = null,
    val f2: Float? = null,
    val f3: Float? = null,
    val fricativeCentroidHz: Float? = null,
    val fricativeZcr: Float? = null,
    val findings: List<AcousticFindingModel> = emptyList(),
)

/**
 * One reference phone under the shared-inventory decision layer: the acoustic
 * features the head read ([gop], [gopRank], [margin], [entropy], [frames]) and
 * the head's [quality] = P(this phone was produced correctly), 0..1. A high
 * [quality] means native-like; the lowest ones drive the utterance score.
 */
@Serializable
data class PhoneQualityModel(
    val phone: String,
    /** What the model heard in this phone's frames, when it was not the target. */
    val produced: String? = null,
    /** Coarse articulatory class the head used as a feature. */
    val phoneClass: String,
    /** Goodness of pronunciation: mean(log p_target - max non-blank log p). 0 is best. */
    val gop: Float,
    /** Per-utterance GOP rank, 0 = the weakest phone of the attempt. */
    val gopRank: Float,
    val margin: Float,
    val entropy: Float,
    val frames: Int,
    /** Head probability that this phone was produced correctly, 0..1. */
    val quality: Float,
)

/**
 * The decision layer's own signal, separate from [PronunciationFeedback.score].
 *
 * [score] is the mean [PhoneQualityModel.quality] of the [worst] phones (up to
 * three), which is what transfers across speakers: an accent lives in the worst
 * phones, and their mean carries the separation the global per-phone mean washes
 * out. [phones] is every reference phone in order.
 *
 * Additive by contract: this never changes the phoneme score or its scale.
 */
@Serializable
data class PronunciationQualityModel(
    /** Mean quality of the worst phones, 0..1; higher is more native-like. */
    val score: Float,
    /** The phones driving [score], worst first (up to three). */
    val worst: List<PhoneQualityModel> = emptyList(),
    /** Every reference phone with its features, in reference order. */
    val phones: List<PhoneQualityModel> = emptyList(),
    /** Stable id of the frozen head, for provenance. */
    val head: String = "",
)

/** A single word's result, so the learner can be told exactly what to work on. */
@Serializable
data class WordFeedbackModel(
    val text: String,
    val score: Int,
    val level: MasteryLevel = MasteryLevel.NOT_YET,
    /**
     * One entry per character of [text], in order, so the UI can mark the exact
     * letters that did not land instead of colouring the whole word. Empty when
     * the character-level alignment was not available.
     */
    val letters: List<LetterFeedbackModel> = emptyList(),
)

/**
 * One reference phone placed on the audio timeline by forced alignment, with its
 * measured prosody. [durationMs], [energy] and [f0Hz] are what stress and
 * intonation are made of, so they let the feedback go beyond "wrong sound".
 */
@Serializable
data class PhoneTimingModel(
    val phone: String,
    val startMs: Int,
    val endMs: Int,
    /** Mean acoustic-model probability for this phone across its frames, 0..1. */
    val confidence: Float,
    /** RMS energy of the audio inside the span. */
    val energy: Float = 0f,
    /** Median fundamental frequency when the span is voiced, else null. */
    val f0Hz: Float? = null,
    val voiced: Boolean = false,
) {
    val durationMs: Int get() = endMs - startMs
}

/** How a reference sound went wrong. */
@Serializable
enum class PhonemeErrorKindModel { SUBSTITUTION, DELETION }

/**
 * A diagnosed pronunciation error: the reference [phoneme] was produced as
 * [actual] (or dropped), and [ruleId]/[examples] point at a targeted drill.
 */
@Serializable
data class PhonemeErrorModel(
    val phoneme: String,
    val actual: String? = null,
    val kind: PhonemeErrorKindModel,
    /** Stable identifier of the rule that fired, e.g. "en.th.stopping". */
    val ruleId: String? = null,
    /** Short human title; the UI may localize by [ruleId] instead. */
    val title: String? = null,
    /** Minimal-pair words that train the contrast (e.g. "think", "sink"). */
    val examples: List<String> = emptyList(),
    /**
     * Index of the reference word the error was heard in, into
     * [PronunciationFeedback.words], or -1 when the word could not be placed.
     */
    val wordIndex: Int = -1,
)

/** Aggregated accuracy for one reference phoneme across many attempts. */
@Serializable
data class PhonemeStatModel(
    val phoneme: String,
    val attempts: Int = 0,
    val correct: Int = 0,
    /** How often each wrong phone was produced when this phoneme was missed. */
    val errors: Map<String, Int> = emptyMap(),
) {
    /** Percent of attempts where the sound was produced perfectly, 0..100. */
    val accuracy: Int get() = if (attempts == 0) 0 else correct * 100 / attempts

    /** The wrong sound this learner reaches for most often, if any. */
    val typicalError: String? get() = errors.maxByOrNull { it.value }?.key
}

/** A learner's phonetic profile: per-sound accuracy, weakest sounds first. */
@Serializable
data class PhoneticProfileModel(
    val stats: List<PhonemeStatModel> = emptyList(),
)

/** Why a stressed vowel did not stand out. */
@Serializable
enum class StressIssueModel {
    /** The stressed vowel was not longer than the unstressed ones. */
    NOT_LONG_ENOUGH,
    /** The stressed vowel was not louder than the unstressed ones. */
    TOO_WEAK,
}

/** A word whose primary stress was not audible, and why. */
@Serializable
data class StressFindingModel(
    val issue: StressIssueModel,
    val phoneme: String,
    /** Index of the word in the sentence, so the UI can point at it. */
    val wordIndex: Int,
    val title: String,
)

/**
 * Measured rhythm and stress of one attempt: which stressed vowels failed to
 * stand out, plus the speaker's median vowel length as a rough tempo signal.
 * Null when the reference carries no stress or there is too little to compare.
 */
@Serializable
data class StressReportModel(
    val findings: List<StressFindingModel> = emptyList(),
    val medianVowelMs: Int = 0,
)

/** Explains a CLOSE phoneme confusion to the learner. */
@Serializable
data class PairHint(
    val expected: String,
    val actual: String,
    val description: String
)

@Serializable
data class PhonemeMatchModel(
    val expected: String,
    val actual: String,
    val status: MatchStatusModel
)

@Serializable
data class LetterFeedbackModel(
    val char: String,
    val status: MatchStatusModel
)

@Serializable
enum class MatchStatusModel { PERFECT, CLOSE, MISSED }

/**
 * The named mastery level of a pronunciation attempt. This is the whole
 * user-facing result: learners are told what they achieved ("Mastered",
 * "Almost"), never a bare percentage that hides *what* to fix.
 *
 * Ordered best to worst so the enum's natural order is the ladder.
 */
@Serializable
enum class MasteryLevel {
    /** Every sound landed exactly - nothing to fix. */
    PERFECT,
    /** Correct and complete enough to count toward progress. */
    MASTERED,
    /** One clear slip; still understandable. */
    ALMOST,
    /** Several slips; practice needed. */
    ROUGH,
    /** Barely recognisable yet. */
    NOT_YET
}
