package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeObservationModel

/** Whether a sound is owed practice, still being trained, or left to rest. */
enum class ReviewStatus { DUE, LEARNING, SETTLED }

/** Why the scheduler chose this interval. */
enum class ReviewReason {
    /** Never measured (or only the old aggregate exists): take a baseline. */
    NEW,

    /** The same error keeps repeating: keep it in play. */
    PERSISTENT,

    /** It was doing better and slipped: reactivate it. */
    LAPSED,

    /** It is getting better: space the checks out. */
    IMPROVING,

    /** Stable and solid: keep it on a long leash. */
    KEEPING,
}

/** When a sound is next worth revisiting, and why. */
data class PhonemeSchedule(
    val phoneme: String,
    val intervalDays: Int,
    val dueDay: Long,
    val status: ReviewStatus,
    val reason: ReviewReason,
)

/**
 * Spaced repetition for phonemes. Improvement is rewarded by longer intervals
 * (practice it less, check back later); a slide or a persistent pattern pulls the
 * interval straight back to daily. Pure and platform-free, so the policy is
 * testable without a clock.
 */
object PhonemeScheduler {

    /** First interval for a sound with no history to go on. */
    const val DEFAULT_INTERVAL = 3

    /** A sound that needs reactivation is due every day. */
    const val MIN_INTERVAL = 1

    /** Longest leash a settled sound can get. */
    const val MAX_INTERVAL = 30

    /** At or above this, a stable sound is considered solid. */
    const val SOLID_ACCURACY = 80

    /** Where the schedule for one sound stands today. */
    fun schedule(
        history: List<PhonemeObservationModel>,
        phoneme: String,
        today: Long,
    ): PhonemeSchedule {
        val trend = PhonemeTrends.of(history, phoneme)
        val pattern = ErrorPatternDetector.detectOne(history, phoneme)

        if (trend.observations == 0) {
            // Nothing dated for this sound yet (e.g. only the old aggregate).
            return PhonemeSchedule(
                phoneme = phoneme,
                intervalDays = MIN_INTERVAL,
                dueDay = today,
                status = ReviewStatus.DUE,
                reason = ReviewReason.NEW,
            )
        }

        val lastDay = trend.points.last().day
        // An improving sound earns space even if it still misses: progress is the
        // point. A slide or a persistent pattern pulls it straight back to daily.
        val reason = when {
            trend.direction == TrendDirection.IMPROVING -> ReviewReason.IMPROVING
            trend.direction == TrendDirection.DECLINING -> ReviewReason.LAPSED
            pattern != null && pattern.confidence >= ErrorPatternDetector.CONFIDENT ->
                ReviewReason.PERSISTENT
            trend.latestAccuracy >= SOLID_ACCURACY -> ReviewReason.KEEPING
            else -> ReviewReason.NEW
        }

        val interval = when (reason) {
            ReviewReason.PERSISTENT, ReviewReason.LAPSED -> MIN_INTERVAL
            ReviewReason.IMPROVING, ReviewReason.KEEPING -> nextInterval(trend.days)
            ReviewReason.NEW -> DEFAULT_INTERVAL
        }
        val dueDay = lastDay + interval
        val status = when {
            dueDay <= today -> ReviewStatus.DUE
            interval >= MAX_INTERVAL -> ReviewStatus.SETTLED
            else -> ReviewStatus.LEARNING
        }
        return PhonemeSchedule(phoneme, interval, dueDay, status, reason)
    }

    /**
     * The interval a sound earns after [daysObserved] distinct days of history:
     * 3, 6, 12, 24, 30 days. More evidence of steady improvement, longer leash.
     */
    fun nextInterval(daysObserved: Int): Int {
        val steps = (daysObserved / 3).coerceIn(0, 5)
        var interval = DEFAULT_INTERVAL
        repeat(steps) { interval = (interval * 2).coerceAtMost(MAX_INTERVAL) }
        return interval.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
    }

    /** Sounds owed a review today, most urgent (shortest interval) first. */
    fun due(
        history: List<PhonemeObservationModel>,
        today: Long,
    ): List<PhonemeSchedule> =
        PhonemeMemory.sounds(history)
            .map { schedule(history, it, today) }
            .filter { it.dueDay <= today }
            .sortedWith(compareBy({ it.intervalDays }, { it.phoneme }))
}
