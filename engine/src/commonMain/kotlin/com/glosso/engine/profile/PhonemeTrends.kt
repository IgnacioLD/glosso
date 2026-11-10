package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeObservationModel

/** Where a sound is heading over the days it has been measured. */
enum class TrendDirection { IMPROVING, STABLE, DECLINING, UNKNOWN }

/** One day's mean accuracy for a sound, when several attempts landed on it. */
data class PhonemeDayPoint(
    val day: Long,
    val accuracy: Int,
    val attempts: Int,
)

/**
 * A sound's real history: the daily points behind it, the first and latest
 * values, and the direction they add up to. This is what lets the app say
 * "/θ/ day 1: 43, day 3: 51, day 7: 63, day 14: 71" instead of only the last
 * score.
 */
data class PhonemeTrend(
    val phoneme: String,
    val points: List<PhonemeDayPoint>,
    val firstAccuracy: Int,
    val latestAccuracy: Int,
    val bestAccuracy: Int,
    val delta: Int,
    val direction: TrendDirection,
    val observations: Int,
) {
    val days: Int get() = points.size
}

/**
 * Reads trends from the dated observation log. Direction compares the first and
 * last windows rather than single points, so one noisy attempt cannot flip it.
 */
object PhonemeTrends {

    /** Fewer distinct days than this is not a trend yet. */
    const val MIN_DAYS = 2

    /** Points of change needed to call a trend improving or declining. */
    const val MIN_DELTA = 8

    /** How many points each end of the comparison averages. */
    const val WINDOW = 3

    fun of(history: List<PhonemeObservationModel>, phoneme: String): PhonemeTrend {
        val obs = PhonemeMemory.series(history, phoneme)
        val points = daily(obs)
        val first = points.firstOrNull()?.accuracy ?: 0
        val latest = points.lastOrNull()?.accuracy ?: 0
        return PhonemeTrend(
            phoneme = phoneme,
            points = points,
            firstAccuracy = first,
            latestAccuracy = latest,
            bestAccuracy = points.maxOfOrNull { it.accuracy } ?: 0,
            delta = latest - first,
            direction = direction(points),
            observations = obs.size,
        )
    }

    /** Every sound the log knows about, each with its trend. */
    fun all(history: List<PhonemeObservationModel>): List<PhonemeTrend> =
        PhonemeMemory.sounds(history).map { of(history, it) }

    /**
     * How far a sound moved on [day]: the mean accuracy of that day minus the one
     * it entered with. The value it entered with is the previous day on record, or
     * the first measurement of the day for a sound seen for the first time, so a
     * single first attempt reads as no change instead of a fake jump. Null when the
     * sound was not measured on [day], so no progress is claimed out of nothing.
     */
    fun todayDelta(history: List<PhonemeObservationModel>, phoneme: String, day: Long): Int? {
        val points = daily(PhonemeMemory.series(history, phoneme))
        val today = points.lastOrNull()?.takeIf { it.day == day } ?: return null
        val entered = points.lastOrNull { it.day < day }?.accuracy ?: today.accuracy
        return today.accuracy - entered
    }

    /** Mean accuracy per day, oldest first. Several attempts on a day average. */
    fun daily(obs: List<PhonemeObservationModel>): List<PhonemeDayPoint> =
        obs.groupBy { it.day }
            .entries
            .sortedBy { it.key }
            .map { (day, list) ->
                PhonemeDayPoint(
                    day = day,
                    accuracy = list.sumOf { it.accuracy } / list.size,
                    attempts = list.size,
                )
            }

    /**
     * Compares the mean of the earliest window against the mean of the latest. At
     * least [MIN_DELTA] points of change are required to leave [TrendDirection.STABLE].
     */
    fun direction(points: List<PhonemeDayPoint>): TrendDirection {
        if (points.size < MIN_DAYS) return TrendDirection.UNKNOWN
        val window = minOf(WINDOW, points.size / 2).coerceAtLeast(1)
        val start = points.take(window).map { it.accuracy }.average()
        val end = points.takeLast(window).map { it.accuracy }.average()
        val delta = end - start
        return when {
            delta >= MIN_DELTA -> TrendDirection.IMPROVING
            delta <= -MIN_DELTA -> TrendDirection.DECLINING
            else -> TrendDirection.STABLE
        }
    }
}
