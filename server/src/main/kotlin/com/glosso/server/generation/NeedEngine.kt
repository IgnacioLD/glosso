package com.glosso.server.generation

/**
 * Turns a learner's measured phonetic profile into needs. A sound only becomes a
 * need with enough evidence (attempts) and enough weakness (score below mastery):
 * one bad take is noise, and a sound that is already mastered is not a need.
 *
 * Pure: no storage, no clock. The caller persists the result with a NeedService.
 */
object NeedEngine {

    /** Trend values a client may report, matching phoneme_profiles.trend. */
    const val TREND_IMPROVING = "improving"
    const val TREND_DECLINING = "declining"

    fun derive(
        profile: LearnerProfile?,
        phonemes: List<PhonemeProfile>,
        config: NeedConfig = NeedConfig.DEFAULT,
    ): List<UserNeed> =
        phonemes
            .filter { it.attempts >= config.minAttempts && it.score < config.masteryThreshold }
            .map { phoneme ->
                UserNeed(
                    userId = phoneme.userId,
                    language = phoneme.language,
                    cefr = profile?.cefr,
                    skill = SKILL_PRONUNCIATION,
                    phoneme = phoneme.phoneme,
                    errorPattern = phoneme.errorPattern,
                    replacementPhoneme = phoneme.replacementPhoneme,
                    position = phoneme.position,
                    priority = priorityOf(phoneme.score, phoneme.confidence, phoneme.trend),
                )
            }
            .sortedWith(compareByDescending<UserNeed> { it.priority }.thenBy { it.phoneme ?: "" })

    /**
     * 0..1 urgency of one sound: how far it is from mastery, tempered by how
     * confident the measurement is, and nudged by which way it is going. A sound
     * that is improving is a little less urgent than one that is sliding.
     */
    fun priorityOf(score: Int, confidence: Float, trend: String? = null): Float {
        val weakness = (100 - score).coerceIn(0, 100) / 100f
        val weight = 0.5f + 0.5f * confidence.coerceIn(0f, 1f)
        val adjusted = when (trend) {
            TREND_DECLINING -> weakness * weight + 0.1f
            TREND_IMPROVING -> weakness * weight - 0.1f
            else -> weakness * weight
        }
        return adjusted.coerceIn(0f, 1f)
    }
}
