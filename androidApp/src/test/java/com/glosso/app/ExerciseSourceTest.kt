package com.glosso.app

import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library must name the base of every exercise it shows. The mapping is
 * built only from references the content already carries, so these tests pin
 * that a task is never linked to a story or lesson the content does not name.
 */
class ExerciseSourceTest {

    private fun exercise(id: String, storyId: String? = null) =
        ExerciseModel(id = id, type = "multiple-choice", prompt = "p", storyId = storyId)

    private fun story(id: String, title: String) = StoryModel(id = id, title = title)

    private fun lesson(id: String, title: String, exerciseIds: List<String>) =
        LessonModel(id = id, title = title, exerciseIds = exerciseIds)

    @Test
    fun `an exercise with a storyId is linked to that story`() {
        val sources = exerciseSources(
            exercises = listOf(exercise("e1", storyId = "s1")),
            lessons = emptyList(),
            stories = listOf(story("s1", "The Blue Cup")),
        )

        val source = sources["e1"]
        assertTrue(source is ExerciseSource.Story)
        assertEquals("The Blue Cup", source?.title)
    }

    @Test
    fun `a storyId is preferred over the lesson when both apply`() {
        val sources = exerciseSources(
            exercises = listOf(exercise("e1", storyId = "s1")),
            lessons = listOf(lesson("l1", "At the airport", listOf("e1"))),
            stories = listOf(story("s1", "The Blue Cup")),
        )

        assertTrue(sources["e1"] is ExerciseSource.Story)
    }

    @Test
    fun `an exercise listed by a lesson is linked to that lesson`() {
        val sources = exerciseSources(
            exercises = listOf(exercise("airport.001")),
            lessons = listOf(lesson("l1", "At the airport", listOf("airport.001", "airport.002"))),
            stories = emptyList(),
        )

        val source = sources["airport.001"]
        assertTrue(source is ExerciseSource.Lesson)
        assertEquals("At the airport", source?.title)
    }

    @Test
    fun `a storyId the app did not load falls back to the lesson`() {
        val sources = exerciseSources(
            exercises = listOf(exercise("e1", storyId = "missing")),
            lessons = listOf(lesson("l1", "At the airport", listOf("e1"))),
            stories = emptyList(),
        )

        assertTrue(sources["e1"] is ExerciseSource.Lesson)
    }

    @Test
    fun `an exercise with no reference to anything gets no source`() {
        val sources = exerciseSources(
            exercises = listOf(exercise("city.001")),
            lessons = listOf(lesson("l1", "At the airport", listOf("airport.001"))),
            stories = listOf(story("s1", "The Blue Cup")),
        )

        assertNull(sources["city.001"])
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `the first lesson that lists an exercise wins`() {
        val sources = exerciseSources(
            exercises = listOf(exercise("e1")),
            lessons = listOf(
                lesson("l1", "First", listOf("e1")),
                lesson("l2", "Second", listOf("e1")),
            ),
            stories = emptyList(),
        )

        assertEquals("First", sources["e1"]?.title)
    }
}
