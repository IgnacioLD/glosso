package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bottom bar's contract: it owns the window only at the four top-level
 * screens, and a Library "read it first" jump hands it to the lesson player
 * while clearing the Library sub-route. A stale route is the regression: it
 * would keep the bar hidden after the player closes, with no back arrow on the
 * lesson list to recover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellBarStateTest {

    @Test
    fun `a library jump opens the player and clears the route, so the bar returns`() {
        val inExerciseLibrary = ShellState(
            destination = GlossoDestination.LIBRARY,
            route = LibraryRoute.EXERCISES,
        )
        assertFalse(inExerciseLibrary.showsBottomBar(lessonActive = false, storyReading = false))

        val opened = inExerciseLibrary.openLessons()

        assertEquals(GlossoDestination.LESSONS, opened.destination)
        assertNull(opened.route)
        // While the player is open it owns the window; once it closes the bar is
        // back, which is only possible because the route was cleared.
        assertFalse(opened.showsBottomBar(lessonActive = true, storyReading = false))
        assertTrue(opened.showsBottomBar(lessonActive = false, storyReading = false))
    }

    @Test
    fun `a dirty route would keep the bar hidden after the player closes`() {
        val stranded = ShellState(
            destination = GlossoDestination.LESSONS,
            route = LibraryRoute.EXERCISES,
        )
        assertFalse(stranded.showsBottomBar(lessonActive = false, storyReading = false))
        assertTrue(stranded.copy(route = null).showsBottomBar(lessonActive = false, storyReading = false))
    }

    @Test
    fun `the four top-level screens show the bar and the library sub-screens do not`() {
        GlossoDestination.entries.forEach { destination ->
            assertTrue(
                "$destination must show the bar",
                ShellState(destination = destination).showsBottomBar(lessonActive = false, storyReading = false),
            )
        }
        LibraryRoute.entries.forEach { route ->
            assertFalse(
                "$route must hide the bar",
                ShellState(destination = GlossoDestination.LIBRARY, route = route)
                    .showsBottomBar(lessonActive = false, storyReading = false),
            )
        }
    }

    @Test
    fun `the stories sub-screen hides the bar, reading or not`() {
        val stories = ShellState(destination = GlossoDestination.LIBRARY, route = LibraryRoute.STORIES)
        assertFalse(stories.showsBottomBar(lessonActive = false, storyReading = true))
        assertFalse(stories.showsBottomBar(lessonActive = false, storyReading = false))
    }
}
