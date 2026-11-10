package com.glosso.app

import kotlin.test.assertEquals
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.DialogueTurn
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.lesson.RecallCard
import com.glosso.engine.model.DrillModel
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The pronunciation step must show a loader, never the "no drills yet" empty
 * state, while its plan is still being requested. Opening a lesson straight into
 * DRILLS leaves `loading` set before `ensureDrills()` runs, so the base load has
 * to count as pending too. This pins that invariant at the state level.
 */
class LessonStateTest {

    private val drill = DrillModel(id = "d1", target = "θ", text = "think")

    @Test
    fun `opening into drills before the plan is requested is pending and not empty`() {
        val opening = LessonState(
            loading = true,
            step = LessonStep.DRILLS,
            drills = emptyList(),
            drillsLoading = false,
        )

        assertTrue(opening.drillsPending, "a plan still on its way must not read as an empty bank")
    }

    @Test
    fun `a plan being fetched is pending`() {
        val fetching = LessonState(step = LessonStep.DRILLS, drillsLoading = true)

        assertTrue(fetching.drillsPending)
    }

    @Test
    fun `a genuinely empty plan is not pending`() {
        val empty = LessonState(
            loading = false,
            step = LessonStep.DRILLS,
            drills = emptyList(),
            drillsLoading = false,
        )

        assertFalse(empty.drillsPending, "an empty bank once loading settled is the honest empty state")
    }

    @Test
    fun `a plan with drills is never pending`() {
        val loaded = LessonState(
            loading = true,
            step = LessonStep.DRILLS,
            drills = listOf(drill),
            drillsLoading = false,
        )

        assertFalse(loaded.drillsPending)
    }

    @Test
    fun `a lesson with nothing to say has no speaking step and counts four steps`() {
        val story = LessonModel(id = "l1", title = "A story", storyId = "s1")
        val dialogue = LessonModel(id = "l2", title = "A dialogue", dialogueId = "d1")

        assertEquals(listOf(LessonStep.BASE, LessonStep.EXERCISES, LessonStep.DRILLS, LessonStep.SUMMARY), lessonSteps(story))
        assertEquals(LessonStep.entries - LessonStep.REVIEW - LessonStep.CONTEXT, lessonSteps(dialogue))
        assertEquals(0.75f, stepFraction(lessonSteps(story), LessonStep.DRILLS))
        assertEquals(0.8f, stepFraction(lessonSteps(dialogue), LessonStep.DRILLS))
    }

    @Test
    fun `once the dialogue is loaded the steps follow the lines it really has`() {
        val lesson = LessonModel(id = "l2", title = "A dialogue", dialogueId = "d1")
        val loaded = DialogueModel(id = "d1", title = "D", turns = listOf(DialogueTurn("A", "Hi there, Ben.")))
        val line = RecallCard(ref = "d1#t0", text = "Hi there, Ben.")

        val unprompted = LessonState(active = lesson, dialogue = loaded, step = LessonStep.SPEAK)
        val prompted = unprompted.copy(speakCards = listOf(line))

        assertEquals(4, unprompted.steps.size)
        assertEquals(5, prompted.steps.size)
        assertEquals(line, prompted.recallCard)
    }

    @Test
    fun `review is a step only when phrases are due and it plays those phrases`() {
        val lesson = LessonModel(id = "l1", title = "A story", storyId = "s1")
        val due = RecallCard(ref = "d9#t3", text = "See you at six.")

        val reviewing = LessonState(active = lesson, step = LessonStep.REVIEW, reviewCards = listOf(due))

        assertEquals(LessonStep.REVIEW, reviewing.steps.first())
        assertEquals(due, reviewing.recallCard)
        assertEquals(null, reviewing.copy(step = LessonStep.BASE).recallCard)
        assertTrue(LessonStep.REVIEW.fromMemory && LessonStep.SPEAK.fromMemory && !LessonStep.DRILLS.fromMemory)
    }

    @Test
    fun `a page keeps the phrases of its own step while the lesson moves on`() {
        val due = RecallCard(ref = "d9#t3", text = "See you at six.")
        // A story lesson: review has phrases, "your turn" has none. After the
        // review the lesson is on BASE, and the leaving review page must still
        // see its own phrases, not the empty list of the live step.
        val moved = LessonState(step = LessonStep.BASE, reviewCards = listOf(due))

        assertEquals(listOf(due), moved.cardsOf(LessonStep.REVIEW))
        assertEquals(emptyList<RecallCard>(), moved.cardsOf(LessonStep.SPEAK))
        assertEquals(emptyList<RecallCard>(), moved.cardsOf(LessonStep.BASE))
        assertEquals(null, moved.recallCard)
    }
}
