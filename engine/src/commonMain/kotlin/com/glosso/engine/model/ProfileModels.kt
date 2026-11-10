package com.glosso.engine.model

import kotlinx.serialization.Serializable

/**
 * One scored attempt's outcome for a single reference phoneme, stamped with the
 * device-local day it happened.
 *
 * The aggregate [PhonemeStatModel] says how a sound is doing overall; this says
 * *when* it did so, which is what a trend is made of. Unlike the aggregate, the
 * log is device-local and never travels to the server, so the date is the
 * learner's own day rather than the server's.
 */
@Serializable
data class PhonemeObservationModel(
    val phoneme: String,
    /** Epoch day (device-local) of the attempt. */
    val day: Long = 0,
    /** Times this phoneme appeared in the attempt. */
    val total: Int = 0,
    /** How many of those landed exactly. */
    val correct: Int = 0,
    /** Wrong phone produced most often this attempt; null when it landed. */
    val error: String? = null,
    /**
     * Curated name of the error, exactly as the engine's error rules name it:
     * "en.th.stopping.t" for TH said as T, "en.h.dropping" for an omission, and
     * so on. This is the (phoneme, pattern) key the content engine indexes its
     * pool by, so the demand for "/θ/ -> /t/ in A1" can be queried later. Null
     * when the contrast is one the rules do not name.
     */
    val pattern: String? = null,
) {
    /** Percent landed this attempt, 0..100. */
    val accuracy: Int get() = if (total <= 0) 0 else correct * 100 / total

    /** True when every occurrence of the sound landed exactly. */
    val landed: Boolean get() = total > 0 && correct == total
}

/** The dated observation log behind a phonetic profile, per language. */
@Serializable
data class PhonemeHistoryModel(
    val observations: List<PhonemeObservationModel> = emptyList(),
)
