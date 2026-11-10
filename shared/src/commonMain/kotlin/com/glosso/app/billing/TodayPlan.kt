package com.glosso.app.billing

import com.glosso.engine.model.LessonModel

/**
 * What "Hoy" offers: the lesson to continue (when one is in progress and not
 * finished) or the next unfinished lesson in teaching order, plus whether opening it needs
 * the paywall. Today and its CTA share this one decision, so the home can never
 * point at a lesson the lesson list would have gated - and, crucially, so Today's
 * own entry point goes through [AccessPolicy] exactly like the list does.
 */
data class TodayPlan(
    val lesson: LessonModel?,
    /** True when [lesson] is the lesson already open, so the CTA says "Continue". */
    val resumable: Boolean,
    /** True when opening [lesson] must show the paywall instead of the lesson. */
    val locked: Boolean,
)

/**
 * Resolves the Today target. [resumeLessonId] is only resumed while
 * [resumeInProgress] is true (a lesson at SUMMARY is done); otherwise the first
 * lesson in teaching order that is not in [finishedLessonIds] is offered, never
 * the first one the server happens to return and never one already finished. [AccessController] already treats an in-progress lesson as unlocked, so
 * a Pro lesson the learner started keeps being finishable.
 */
fun todayPlan(
    lessons: List<LessonModel>,
    themeOrder: List<String>,
    resumeLessonId: String?,
    resumeInProgress: Boolean,
    status: SubscriptionStatus,
    productAccess: ProductAccess = ProductAccess.Unknown,
    finishedLessonIds: Collection<String> = emptyList(),
): TodayPlan {
    val access = AccessController(status, resumeLessonId, productAccess)
    val inProgress = resumeLessonId?.let { id -> lessons.firstOrNull { it.id == id } }
    val resumable = inProgress != null && resumeInProgress
    val lesson = if (resumable) inProgress else AccessPolicy.nextLesson(lessons, themeOrder, finishedLessonIds)
    return TodayPlan(
        lesson = lesson,
        resumable = resumable,
        locked = lesson != null && access.isLocked(lesson),
    )
}
