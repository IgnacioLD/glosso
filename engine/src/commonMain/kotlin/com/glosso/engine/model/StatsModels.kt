package com.glosso.engine.model

import kotlinx.serialization.Serializable

/** Number of sentences first-mastered on a given day. Date is "YYYY-MM-DD" (device-local). */
@Serializable
data class MasteryTimelinePoint(
    val date: String,
    val count: Int
)

/** Cumulative accuracy counters for a single phoneme (IPA symbol). */
@Serializable
data class PhonemeStat(
    val phoneme: String,
    val total: Int,
    val missed: Int
) {
    val missRatio: Float
        get() = if (total > 0) missed.toFloat() / total.toFloat() else 0f
}

/** Snapshot of the spaced-repetition review queue. */
@Serializable
data class ReviewBacklog(
    val dueCount: Int,
    val scheduledCount: Int
)

/** Everything the stats screen needs, scoped to the current target language. */
@Serializable
data class UserStats(
    val timeline: List<MasteryTimelinePoint> = emptyList(),
    val activityDates: List<String> = emptyList(),
    val weakPhonemes: List<PhonemeStat> = emptyList(),
    val reviewBacklog: ReviewBacklog = ReviewBacklog(0, 0),
    val totalMastered: Int = 0,
    val totalActivityDays: Int = 0
)
