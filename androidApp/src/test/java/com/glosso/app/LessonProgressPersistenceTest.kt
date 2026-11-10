package com.glosso.app

import android.content.Context
import android.content.SharedPreferences
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.keyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Protects the persistence behind the startup level: clearing the lesson in
 * progress (on finish) must never wipe the manual choice or the last completed
 * level, or a cold start falls back to A1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LessonProgressPersistenceTest {

    private lateinit var prefs: SharedPreferences

    /** The store the app writes through; [prefs] reads the same file directly. */
    private lateinit var store: KeyValueStore

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("glosso_progress_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = keyValueStore(RuntimeEnvironment.getApplication(), "glosso_progress_test")
    }

    private fun startupLevel(snapshot: ProgressSnapshot): String = resolveStartupLevel(
        resumeLessonId = snapshot.resumeLessonId,
        resumeLevel = snapshot.resumeLevel,
        lastCompletedLevel = snapshot.lastCompletedLevel,
        manualLevel = snapshot.manualLevel,
    )

    @Test
    fun `saveLessonProgress records the lesson, its step and its level`() {
        saveLessonProgress(store, "l27", LessonStep.EXERCISES, "B1")

        assertEquals("l27", prefs.getString(ProgressKeys.LESSON_ID, null))
        assertEquals("EXERCISES", prefs.getString(ProgressKeys.LESSON_STEP, null))
        assertEquals("B1", prefs.getString(ProgressKeys.RESUME_LEVEL, null))
    }

    @Test
    fun `clearing the lesson in progress keeps the manual and last completed levels`() {
        saveManualLevel(store, "B1")
        saveLessonProgress(store, "l1", LessonStep.DRILLS, "B1")
        recordFinishedLesson(store, "B1")

        clearLessonProgress(store)

        val snapshot = readProgressSnapshot(store)
        assertNull(snapshot.resumeLessonId)
        assertNull(snapshot.resumeLevel)
        assertEquals("B1", snapshot.manualLevel)
        assertEquals("B1", snapshot.lastCompletedLevel)
    }

    @Test
    fun `a cold start after finishing keeps the manual level over the finished one`() {
        saveManualLevel(store, "ALL")
        recordFinishedLesson(store, "B2")
        clearLessonProgress(store)

        assertEquals("ALL", startupLevel(readProgressSnapshot(store)))
    }

    @Test
    fun `a cold start with a lesson in progress resumes its level`() {
        saveManualLevel(store, "A1")
        recordFinishedLesson(store, "A1")
        saveLessonProgress(store, "l27", LessonStep.BASE, "B2")

        assertEquals("B2", startupLevel(readProgressSnapshot(store)))
    }

    @Test
    fun `a manual level survives finishing a lesson in another level`() {
        saveManualLevel(store, "A2")
        saveLessonProgress(store, "l40", LessonStep.BASE, "B2")
        recordFinishedLesson(store, "B2")
        clearLessonProgress(store)

        val snapshot = readProgressSnapshot(store)
        assertEquals("A2", snapshot.manualLevel)
        assertEquals("B2", snapshot.lastCompletedLevel)
        assertEquals("A2", startupLevel(snapshot))
    }
}
