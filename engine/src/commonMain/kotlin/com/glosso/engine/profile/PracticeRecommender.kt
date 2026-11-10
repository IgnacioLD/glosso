package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.phonetics.PhoneticProfile

/** A row of today's practice: train one sound, or put sounds into phrases. */
enum class PracticeKind { SOUND, PHRASES }

/** Why this row is in the plan, so the UI can explain it. */
enum class PracticeReason {
    /** A pattern the learner keeps hitting. */
    PERSISTENT,

    /** It was better and slipped. */
    LAPSED,

    /** Low accuracy and not trending either way. */
    WEAK,

    /** Improving: a short check-in to confirm the gain. */
    CHECK_IN,
}

data class PracticeItem(
    val kind: PracticeKind,
    /** The sound for a [PracticeKind.SOUND] row; null for phrases. */
    val phoneme: String? = null,
    val minutes: Int = 2,
    val reason: PracticeReason = PracticeReason.WEAK,
)

/**
 * "Your practice today": a short, prioritised plan built from the learner's own
 * weak sounds and where each one stands, never from a fixed list. Example:
 * `/θ/ 2 min, /ð/ 2 min, phrases 1 min`.
 */
data class DailyPracticePlan(val items: List<PracticeItem> = emptyList()) {
    val totalMinutes: Int get() = items.sumOf { it.minutes }
    val isEmpty: Boolean get() = items.isEmpty()
    val sounds: List<String> get() = items.mapNotNull { it.phoneme }
}

/**
 * Turns the profile and its history into today's plan. Priority blends how weak
 * a sound is, whether a pattern is firing, and where the trend and schedule say
 * it is. Sounds go first in two-minute blocks; a one-minute phrases row puts them
 * into context, and any leftover minute goes to the top sound.
 *
 * Pure and platform-free.
 */
object PracticeRecommender {

    const val DEFAULT_BUDGET_MINUTES = 5
    const val SOUND_MINUTES = 2
    const val PHRASES_MINUTES = 1

    fun plan(
        profile: PhoneticProfileModel,
        history: List<PhonemeObservationModel>,
        today: Long,
        budgetMinutes: Int = DEFAULT_BUDGET_MINUTES,
    ): DailyPracticePlan {
        val candidates = profile.stats
            .filter { it.attempts >= PhoneticProfile.MIN_ATTEMPTS && it.accuracy < 100 }
            .map { stat -> candidate(stat, history, today) }
            .sortedWith(compareByDescending<Candidate> { it.priority }.thenBy { it.stat.phoneme })

        if (candidates.isEmpty() || budgetMinutes <= 0) return DailyPracticePlan()

        val phraseReserve = if (budgetMinutes > SOUND_MINUTES) PHRASES_MINUTES else 0
        val maxSounds = ((budgetMinutes - phraseReserve) / SOUND_MINUTES).coerceAtLeast(1)
        val chosen = candidates.take(maxSounds)

        val items = ArrayList<PracticeItem>(chosen.size + 1)
        chosen.forEach { items += PracticeItem(PracticeKind.SOUND, it.stat.phoneme, SOUND_MINUTES, it.reason) }
        if (phraseReserve > 0 && items.isNotEmpty()) {
            items += PracticeItem(PracticeKind.PHRASES, minutes = PHRASES_MINUTES)
        }
        // Spend any leftover minute on the top sound rather than leave it idle.
        val leftover = budgetMinutes - items.sumOf { it.minutes }
        if (leftover > 0 && items.isNotEmpty()) {
            items[0] = items[0].copy(minutes = items[0].minutes + leftover)
        }
        return DailyPracticePlan(items)
    }

    private data class Candidate(
        val stat: PhonemeStatModel,
        val priority: Float,
        val reason: PracticeReason,
    )

    private fun candidate(
        stat: PhonemeStatModel,
        history: List<PhonemeObservationModel>,
        today: Long,
    ): Candidate {
        val trend = PhonemeTrends.of(history, stat.phoneme)
        val pattern = ErrorPatternDetector.detectOne(history, stat.phoneme)
        val schedule = PhonemeScheduler.schedule(history, stat.phoneme, today)

        var priority = (100 - stat.accuracy).toFloat()
        pattern?.let { priority += 40f * it.confidence }
        when (trend.direction) {
            TrendDirection.DECLINING -> priority += 15f
            TrendDirection.IMPROVING -> if (stat.accuracy >= 70) priority -= 15f
            else -> Unit
        }
        if (schedule.status == ReviewStatus.SETTLED) priority -= 20f

        val reason = when {
            pattern != null && pattern.confidence >= ErrorPatternDetector.CONFIDENT ->
                PracticeReason.PERSISTENT
            trend.direction == TrendDirection.DECLINING -> PracticeReason.LAPSED
            trend.direction == TrendDirection.IMPROVING -> PracticeReason.CHECK_IN
            else -> PracticeReason.WEAK
        }
        return Candidate(stat, priority, reason)
    }
}
