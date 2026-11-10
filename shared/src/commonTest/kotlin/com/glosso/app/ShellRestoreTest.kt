package com.glosso.app

import androidx.compose.runtime.saveable.SaverScope
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shell comes back after a rotation or a process death: the tab, the
 * Library sub-screen and what the player or reader had open. Before, the
 * shell state was plain `remember` and both sent the learner back to Today.
 */
class ShellRestoreTest {

    private val anything = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun roundTrip(state: ShellState): ShellState {
        val saved = with(ShellStateSaver) { anything.save(state) }!!
        return ShellStateSaver.restore(saved)!!
    }

    @Test
    fun `every tab and library route survives the saver`() {
        for (destination in GlossoDestination.entries) {
            for (route in LibraryRoute.entries + listOf(null)) {
                val state = ShellState(destination, route)
                assertEquals(state, roundTrip(state))
            }
        }
    }

    @Test
    fun `the open lesson and story ids survive the saver`() {
        val state = ShellState(GlossoDestination.LESSONS, null, openLessonId = "l7", openStoryId = "s3")
        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `an unknown saved name falls back to Today without a route`() {
        val restored = ShellStateSaver.restore(listOf("GONE", "ALSO_GONE", null, null))
        assertEquals(ShellState(), restored)
    }

    private val lesson = LessonModel(id = "l1", title = "A Call Home")
    private val other = LessonModel(id = "l2", title = "Other")

    @Test
    fun `a lesson waits for the list before it is reopened`() {
        assertEquals(Reopen.Wait, lessonToReopen("l1", LessonState()))
        assertEquals(Reopen.Wait, lessonToReopen("l1", LessonState(loading = true)))
    }

    @Test
    fun `the lesson in progress reopens once the list has it`() {
        val state = LessonState(lessons = listOf(other, lesson), resumeLessonId = "l1", resumeStep = LessonStep.DRILLS)
        assertEquals(Reopen.Open(lesson), lessonToReopen("l1", state))
    }

    @Test
    fun `a lesson that is not the one in progress still reopens`() {
        val state = LessonState(lessons = listOf(lesson), resumeLessonId = "l2")
        assertEquals(Reopen.Open(lesson), lessonToReopen("l1", state))
    }

    @Test
    fun `a finished lesson or a vanished one or a failed load leave the lesson list`() {
        val finished = LessonState(lessons = listOf(lesson), resumeLessonId = "l1", resumeStep = LessonStep.SUMMARY)
        assertEquals(Reopen.GiveUp, lessonToReopen("l1", finished))
        assertEquals(Reopen.GiveUp, lessonToReopen("l1", LessonState(lessons = listOf(other))))
        assertEquals(Reopen.GiveUp, lessonToReopen("l1", LessonState(error = "offline")))
    }

    @Test
    fun `nothing is reopened over a lesson that is already open`() {
        val state = LessonState(lessons = listOf(lesson), active = other)
        assertEquals(Reopen.GiveUp, lessonToReopen("l1", state))
    }

    private val story = StoryModel(id = "s1", title = "The Red Door")

    @Test
    fun `a story reopens once the bank has it`() {
        assertEquals(Reopen.Wait, storyToReopen("s1", StoriesState(loading = true)))
        assertEquals(Reopen.Open(story), storyToReopen("s1", StoriesState(stories = listOf(story))))
    }

    @Test
    fun `a vanished story or a failed load or an open story give up`() {
        val elsewhere = StoryModel(id = "s2", title = "Other")
        assertEquals(Reopen.GiveUp, storyToReopen("s1", StoriesState(stories = listOf(elsewhere))))
        assertEquals(Reopen.GiveUp, storyToReopen("s1", StoriesState(error = "offline")))
        assertEquals(Reopen.GiveUp, storyToReopen("s1", StoriesState(stories = listOf(story), reading = elsewhere)))
    }
}
