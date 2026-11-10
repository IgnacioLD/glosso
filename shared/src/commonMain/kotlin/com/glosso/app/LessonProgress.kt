package com.glosso.app

import com.glosso.app.platform.KeyValueStore

/**
 * The keys of the lesson-progress store. The lesson in progress and the two
 * levels are deliberately separate entries: finishing a lesson clears only the
 * first, so the learner's level choice and the level they last completed both
 * survive a cold start.
 */
/** The lesson-progress store's file (the SharedPreferences name on Android). */
const val PROGRESS_FILE = "glosso_progress"

object ProgressKeys {
    const val LESSON_ID = "lesson_id"
    const val LESSON_STEP = "lesson_step"
    const val RESUME_LEVEL = "resume_level"
    const val LEVEL = "level"
    const val LAST_COMPLETED_LEVEL = "last_completed_level"
}

/** What the app needs to resolve the level and the lesson to resume. */
data class ProgressSnapshot(
    val resumeLessonId: String?,
    val resumeLevel: String?,
    val lastCompletedLevel: String?,
    val manualLevel: String?,
)

fun readProgressSnapshot(prefs: KeyValueStore): ProgressSnapshot = ProgressSnapshot(
    resumeLessonId = prefs.getString(ProgressKeys.LESSON_ID),
    resumeLevel = prefs.getString(ProgressKeys.RESUME_LEVEL),
    lastCompletedLevel = prefs.getString(ProgressKeys.LAST_COMPLETED_LEVEL),
    manualLevel = prefs.getString(ProgressKeys.LEVEL),
)

/**
 * The step saved for the lesson in progress, or null when there is none or it
 * names a step this build does not know. A lesson is never resumed before its
 * base: the review and the context belong to opening it from the start.
 */
fun savedLessonStep(name: String?): LessonStep? =
    name?.let { runCatching { LessonStep.valueOf(it) }.getOrNull() }?.coerceAtLeast(LessonStep.BASE)

/** Stores the lesson in progress and its level. Never touches the learner's levels. */
fun saveLessonProgress(
    prefs: KeyValueStore,
    lessonId: String,
    step: LessonStep,
    lessonLevel: String,
) {
    prefs.edit {
        putString(ProgressKeys.LESSON_ID, lessonId)
        putString(ProgressKeys.LESSON_STEP, step.name)
        putString(ProgressKeys.RESUME_LEVEL, lessonLevel)
    }
}

/** Remembers the level of the last finished lesson. */
fun recordFinishedLesson(prefs: KeyValueStore, lessonLevel: String) {
    prefs.edit { putString(ProgressKeys.LAST_COMPLETED_LEVEL, lessonLevel) }
}

/**
 * Clears only the lesson in progress. The manual level and the last completed
 * level stay, or the app would fall back to A1 on the next cold start.
 */
fun clearLessonProgress(prefs: KeyValueStore) {
    prefs.edit {
        remove(ProgressKeys.LESSON_ID)
        remove(ProgressKeys.LESSON_STEP)
        remove(ProgressKeys.RESUME_LEVEL)
    }
}

/** Records the level the learner picked by hand, so it wins on the next cold start. */
fun saveManualLevel(prefs: KeyValueStore, level: String) {
    prefs.edit { putString(ProgressKeys.LEVEL, level) }
}
