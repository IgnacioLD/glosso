package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeObservationModel

/**
 * A persistent difficulty the learner keeps running into: the same sound going
 * wrong the same way for several attempts in a row, not one bad take.
 */
data class PhonemePattern(
    val phoneme: String,
    /** The wrong phone it keeps becoming, or [PhonemeMemory.DROPPED]. */
    val error: String,
    /** Consecutive attempts it has failed the same way. */
    val evidence: Int,
    /** 0..1; grows with evidence and falls as the sound starts to improve. */
    val confidence: Float,
    val trend: TrendDirection,
)

/**
 * Detects patterns, not isolated errors.
 *
 * A single bad attempt never becomes a recommendation: the same sound must fail
 * the same way [MIN_EVIDENCE] times in a row. Confidence then grows with each
 * further miss and is damped when the trend is improving, so a sound that is
 * getting better stops being called a persistent difficulty.
 *
 * Pure and platform-free.
 */
object ErrorPatternDetector {

    /** Same error this many times in a row before it is a pattern. */
    const val MIN_EVIDENCE = 3

    /** Only this many recent attempts are considered. */
    const val WINDOW = 12

    /** Confidence at or above this is worth telling the learner about. */
    const val CONFIDENT = 0.6f

    /** Patterns with the most confidence first; a single failure is never one. */
    fun detect(
        history: List<PhonemeObservationModel>,
        minEvidence: Int = MIN_EVIDENCE,
        limit: Int = 5,
    ): List<PhonemePattern> =
        PhonemeMemory.sounds(history)
            .mapNotNull { detectOne(history, it, minEvidence) }
            .sortedWith(compareByDescending<PhonemePattern> { it.confidence }.thenBy { it.phoneme })
            .take(limit)

    /** Only the patterns confident enough to name to the learner. */
    fun persistent(
        history: List<PhonemeObservationModel>,
        minEvidence: Int = MIN_EVIDENCE,
    ): List<PhonemePattern> = detect(history, minEvidence).filter { it.confidence >= CONFIDENT }

    /** The pattern for one sound, or null when there is not enough evidence. */
    fun detectOne(
        history: List<PhonemeObservationModel>,
        phoneme: String,
        minEvidence: Int = MIN_EVIDENCE,
    ): PhonemePattern? {
        val recent = PhonemeMemory.series(history, phoneme).takeLast(WINDOW)
        if (recent.size < minEvidence) return null

        // Trailing run of misses, newest last. Any landing breaks the run, so a
        // sound that starts landing stops being a pattern.
        val trailing = recent.takeLastWhile { !it.landed }
        if (trailing.size < minEvidence) return null

        // "Same error": the consecutive misses must agree on the wrong phone.
        val errors = trailing.map { it.error ?: PhonemeMemory.DROPPED }.distinct()
        if (errors.size != 1) return null

        val evidence = trailing.size
        val base = 0.6f + 0.1f * (evidence - minEvidence)
        val trend = PhonemeTrends.of(history, phoneme).direction
        val damp = when (trend) {
            TrendDirection.IMPROVING -> 0.6f
            TrendDirection.DECLINING -> 1.15f
            else -> 1f
        }
        return PhonemePattern(
            phoneme = phoneme,
            error = errors.first(),
            evidence = evidence,
            confidence = (base * damp).coerceIn(0f, 1f),
            trend = trend,
        )
    }
}
