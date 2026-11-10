package com.glosso.app

import android.content.Context
import android.content.SharedPreferences
import com.glosso.engine.lesson.RecallCard
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.LessonNote
import com.glosso.engine.model.NoteKind
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
 * The "Context" step: present only for a lesson with notes, right before the
 * base (after a due review), and never a place a saved lesson resumes into.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LessonContextStepTest {

    private val note = LessonNote(NoteKind.ETIQUETTE, "Ты o вы", "Con desconocidos se usa вы.")
    private val plain = LessonModel(id = "l1", title = "A dialogue", dialogueId = "d1")
    private val withNotes = plain.copy(id = "l2", notes = listOf(note))

    private lateinit var prefs: SharedPreferences

    /** The store the app writes through; [prefs] reads the same file directly. */
    private lateinit var store: KeyValueStore

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("glosso_progress_context_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = keyValueStore(RuntimeEnvironment.getApplication(), "glosso_progress_context_test")
    }

    @Test
    fun `a lesson with notes opens with the context step`() {
        assertEquals(
            listOf(LessonStep.CONTEXT, LessonStep.BASE, LessonStep.EXERCISES, LessonStep.SPEAK, LessonStep.DRILLS, LessonStep.SUMMARY),
            lessonSteps(withNotes),
        )
        assertEquals(LessonStep.CONTEXT, LessonState(active = withNotes).steps.first())
    }

    @Test
    fun `a lesson without notes has no context step`() {
        assertEquals(LessonStep.BASE, lessonSteps(plain).first())
        assertEquals(LessonStep.BASE, LessonState(active = plain).steps.first())
        assertEquals(0.2f, stepFraction(lessonSteps(plain), LessonStep.BASE))
    }

    @Test
    fun `a due review comes first and the context sets the scene right before the base`() {
        val due = RecallCard(ref = "d9#t3", text = "See you at six.")
        val state = LessonState(active = withNotes, step = LessonStep.REVIEW, reviewCards = listOf(due))

        assertEquals(listOf(LessonStep.REVIEW, LessonStep.CONTEXT, LessonStep.BASE), state.steps.take(3))
    }

    @Test
    fun `saved steps from earlier builds still restore`() {
        for (step in listOf(LessonStep.BASE, LessonStep.EXERCISES, LessonStep.SPEAK, LessonStep.DRILLS, LessonStep.SUMMARY)) {
            saveLessonProgress(store, "l1", step, "A1")
            assertEquals(step, savedLessonStep(prefs.getString(ProgressKeys.LESSON_STEP, null)))
        }
    }

    @Test
    fun `a lesson never resumes before its base, and an unknown step is ignored`() {
        assertEquals(LessonStep.BASE, savedLessonStep("CONTEXT"))
        assertEquals(LessonStep.BASE, savedLessonStep("REVIEW"))
        assertNull(savedLessonStep("WARMUP"))
        assertNull(savedLessonStep(""))
        assertNull(savedLessonStep(null))
    }
}
