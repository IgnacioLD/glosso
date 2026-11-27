package com.glosso.app

import android.content.Context
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.keyValueStore
import com.glosso.engine.lesson.RecallCard
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.LessonNote
import com.glosso.engine.model.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Going straight to pronunciation: the "go to pronunciation" shortcut and the
 * "pronunciation only" setting. The step bar shows only the steps taken, a
 * resumed lesson remembers the jump, and a lesson with no drills falls back to
 * its whole flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PronunciationShortcutTest {

    private val note = LessonNote(NoteKind.ETIQUETTE, "Tú o usted", "Con desconocidos, usted.")
    private val lesson = LessonModel(id = "l1", title = "A dialogue", dialogueId = "d1", notes = listOf(note))
    private val dialogue = DialogueModel(id = "d1", title = "At the café")
    private val due = RecallCard(ref = "d9#t3", text = "Hasta luego.")
    private val line = RecallCard(ref = "d1#t1", text = "Hola.")

    private lateinit var store: KeyValueStore

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("glosso_progress_shortcut_test", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = keyValueStore(RuntimeEnvironment.getApplication(), "glosso_progress_shortcut_test")
    }

    @Test
    fun `with the setting off a lesson opens at its start`() {
        assertEquals(LessonStep.CONTEXT, firstLessonStep(LessonStep.BASE, due = false, hasNotes = true, pronunciationOnly = false))
        assertEquals(LessonStep.REVIEW, firstLessonStep(LessonStep.BASE, due = true, hasNotes = true, pronunciationOnly = false))
        assertEquals(LessonStep.EXERCISES, firstLessonStep(LessonStep.EXERCISES, due = false, hasNotes = true, pronunciationOnly = false))
    }

    @Test
    fun `with the setting on a lesson opens at pronunciation, after a due review`() {
        assertEquals(LessonStep.DRILLS, firstLessonStep(LessonStep.BASE, due = false, hasNotes = true, pronunciationOnly = true))
        assertEquals(LessonStep.REVIEW, firstLessonStep(LessonStep.BASE, due = true, hasNotes = true, pronunciationOnly = true))
        // Resumed half-way, it still skips what is left before pronunciation.
        assertEquals(LessonStep.DRILLS, firstLessonStep(LessonStep.SPEAK, due = false, hasNotes = true, pronunciationOnly = true))
    }

    @Test
    fun `the setting shows only the steps taken`() {
        val opened = LessonState(active = lesson, dialogue = dialogue, step = LessonStep.DRILLS, skippedAfter = LessonStep.REVIEW)
        assertEquals(listOf(LessonStep.DRILLS, LessonStep.SUMMARY), opened.steps)

        val reviewing = opened.copy(step = LessonStep.REVIEW, reviewCards = listOf(due))
        assertEquals(listOf(LessonStep.REVIEW, LessonStep.DRILLS, LessonStep.SUMMARY), reviewing.steps)
        // nextStep() after the review takes the first step after it: pronunciation.
        assertEquals(LessonStep.DRILLS, reviewing.steps.first { it > LessonStep.REVIEW })
    }

    @Test
    fun `the shortcut is offered before pronunciation only`() {
        val base = LessonState(active = lesson, dialogue = dialogue, speakCards = listOf(line), step = LessonStep.BASE)
        for (step in listOf(LessonStep.CONTEXT, LessonStep.BASE, LessonStep.EXERCISES, LessonStep.SPEAK)) {
            assertTrue(step.name, base.copy(step = step).canJumpToDrills)
        }
        for (step in listOf(LessonStep.REVIEW, LessonStep.DRILLS, LessonStep.SUMMARY)) {
            assertFalse(step.name, base.copy(step = step).canJumpToDrills)
        }
        assertFalse("not before the content is there", base.copy(dialogue = null).canJumpToDrills)
        assertFalse("not when the lesson has no drills", base.copy(noDrills = true).canJumpToDrills)
    }

    @Test
    fun `the shortcut keeps the steps taken and drops the skipped ones`() {
        val jumped = LessonState(
            active = lesson,
            dialogue = dialogue,
            speakCards = listOf(line),
            step = LessonStep.DRILLS,
            skippedAfter = LessonStep.BASE,
        )
        assertEquals(listOf(LessonStep.CONTEXT, LessonStep.BASE, LessonStep.DRILLS, LessonStep.SUMMARY), jumped.steps)
        assertEquals(LessonStep.SUMMARY, jumped.steps.first { it > LessonStep.DRILLS })
        assertEquals(0.75f, stepFraction(jumped.steps, LessonStep.DRILLS))
    }

    @Test
    fun `a jump that finds no drills goes back to the normal flow`() {
        val shortcut = LessonState(active = lesson, dialogue = dialogue, step = LessonStep.DRILLS, skippedAfter = LessonStep.EXERCISES)
        assertEquals(LessonStep.EXERCISES, shortcut.drillsFallback)

        val setting = shortcut.copy(skippedAfter = LessonStep.REVIEW)
        assertEquals(LessonStep.CONTEXT, setting.drillsFallback)
        assertEquals(LessonStep.BASE, setting.copy(active = lesson.copy(notes = emptyList())).drillsFallback)

        assertNull("the normal flow has nothing to go back to", shortcut.copy(skippedAfter = null).drillsFallback)
    }

    @Test
    fun `a resumed lesson remembers the jump and a finished one forgets it`() {
        saveLessonProgress(store, "l1", LessonStep.DRILLS, "A1", skippedAfter = LessonStep.BASE)
        assertEquals(LessonStep.DRILLS, savedLessonStep(store.getString(ProgressKeys.LESSON_STEP)))
        assertEquals(LessonStep.BASE, savedSkippedAfter(store.getString(ProgressKeys.LESSON_SKIPPED_AFTER)))
        assertEquals(
            listOf(LessonStep.CONTEXT, LessonStep.BASE, LessonStep.DRILLS, LessonStep.SUMMARY),
            lessonSteps(lesson, skippedAfter = LessonStep.BASE),
        )

        saveLessonProgress(store, "l1", LessonStep.EXERCISES, "A1")
        assertNull(savedSkippedAfter(store.getString(ProgressKeys.LESSON_SKIPPED_AFTER)))

        saveLessonProgress(store, "l1", LessonStep.DRILLS, "A1", skippedAfter = LessonStep.REVIEW)
        clearLessonProgress(store)
        assertNull(savedSkippedAfter(store.getString(ProgressKeys.LESSON_SKIPPED_AFTER)))
        assertNull(savedSkippedAfter("WARMUP"))
    }
}
