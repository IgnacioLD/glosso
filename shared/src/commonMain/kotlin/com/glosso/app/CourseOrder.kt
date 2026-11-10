package com.glosso.app

import com.glosso.engine.model.LessonModel

/**
 * The order the course is taught in. The lesson list, Today and the "what is
 * next" decision all read it, so they can never disagree on the next lesson.
 */
object CourseOrder {

    /**
     * The theme order the course is taught in: the manifest order first, then any
     * theme the manifest did not list, in the order it first appears.
     */
    fun blockOrder(lessons: List<LessonModel>, themeOrder: List<String> = emptyList()): List<String> {
        val present = lessons.asSequence()
            .map { it.theme }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val known = themeOrder.filter { it in present }
        return known + present.filterNot { it in known }
    }

    /**
     * The order of the lessons inside one block: by level, then by the kind of
     * base (a dialogue before a story before an article), then by id.
     */
    val lessonOrder: Comparator<LessonModel> = compareBy({ it.level }, { baseRank(it) }, { it.id })

    private fun baseRank(lesson: LessonModel): Int = when {
        lesson.dialogueId != null -> 0
        lesson.storyId != null -> 1
        lesson.articleId != null -> 2
        else -> 3
    }

    /** Every lesson in the order the course is taught: block by block, then [lessonOrder]. */
    fun teachingOrder(lessons: List<LessonModel>, themeOrder: List<String> = emptyList()): List<LessonModel> {
        val byTheme = lessons.groupBy { it.theme }
        return blockOrder(lessons, themeOrder).flatMap { byTheme[it].orEmpty().sortedWith(lessonOrder) }
    }

    /** The first lesson in teaching order: the one a new learner should meet. */
    fun firstLesson(lessons: List<LessonModel>, themeOrder: List<String> = emptyList()): LessonModel? =
        teachingOrder(lessons, themeOrder).firstOrNull()

    /**
     * The first lesson in teaching order the learner has not finished yet. Once
     * every lesson is finished it falls back to the first one rather than to an
     * empty card.
     */
    fun nextLesson(
        lessons: List<LessonModel>,
        themeOrder: List<String> = emptyList(),
        finishedLessonIds: Collection<String> = emptyList(),
    ): LessonModel? {
        val ordered = teachingOrder(lessons, themeOrder)
        return ordered.firstOrNull { it.id !in finishedLessonIds } ?: ordered.firstOrNull()
    }
}

/**
 * What "Today" offers: the lesson to continue (when one is in progress and not
 * finished) or the next unfinished lesson in teaching order.
 */
data class TodayPlan(
    val lesson: LessonModel?,
    /** True when [lesson] is the lesson already open, so the CTA says "Continue". */
    val resumable: Boolean,
)

/**
 * Resolves the Today target. [resumeLessonId] is only resumed while
 * [resumeInProgress] is true (a lesson at SUMMARY is done); otherwise the first
 * lesson in teaching order that is not in [finishedLessonIds] is offered.
 */
fun todayPlan(
    lessons: List<LessonModel>,
    themeOrder: List<String>,
    resumeLessonId: String?,
    resumeInProgress: Boolean,
    finishedLessonIds: Collection<String> = emptyList(),
): TodayPlan {
    val inProgress = resumeLessonId?.let { id -> lessons.firstOrNull { it.id == id } }
    val resumable = inProgress != null && resumeInProgress
    val lesson = if (resumable) inProgress else CourseOrder.nextLesson(lessons, themeOrder, finishedLessonIds)
    return TodayPlan(lesson = lesson, resumable = resumable)
}
