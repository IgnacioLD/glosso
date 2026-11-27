package com.glosso.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every way into a lesson (a row in the list, the hero, Today, a restore after
 * process death) asks [LessonState.startStepFor] where it starts, so a lesson in
 * progress resumes the same way from all of them.
 */
class LessonResumeTest {

    @Test
    fun `the lesson in progress opens at the step it was left`() {
        val state = LessonState(resumeLessonId = "l1", resumeStep = LessonStep.EXERCISES)

        assertEquals(LessonStep.EXERCISES, state.startStepFor("l1"))
    }

    @Test
    fun `any other lesson opens from its start`() {
        val state = LessonState(resumeLessonId = "l1", resumeStep = LessonStep.EXERCISES)

        assertEquals(LessonStep.BASE, state.startStepFor("l2"))
        assertEquals(LessonStep.BASE, LessonState().startStepFor("l1"))
    }

    @Test
    fun `a finished lesson opens from its start`() {
        val state = LessonState(resumeLessonId = "l1", resumeStep = LessonStep.SUMMARY)

        assertEquals(LessonStep.BASE, state.startStepFor("l1"))
    }

    @Test
    fun `a step saved by another app version opens the lesson from its start`() {
        // Unknown names read back as nothing, and the steps before the base are
        // never resumed into.
        listOf("RETIRED_STEP", "REVIEW", "CONTEXT", "", null).forEach { saved ->
            val state = LessonState(resumeLessonId = "l1", resumeStep = savedLessonStep(saved) ?: LessonStep.BASE)
            assertEquals(LessonStep.BASE, state.startStepFor("l1"), "saved step $saved")
        }
    }
}
