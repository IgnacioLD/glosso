package com.glosso.app

import com.glosso.engine.model.LessonModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseOrderTest {

    private fun lesson(id: String, level: String = "A1", theme: String = "city") =
        LessonModel(id = id, level = level, theme = theme, title = id)

    // The catalog arrives in server order, which is not the teaching order: only
    // the published theme order can pick the first lesson.
    private val catalog = listOf(
        lesson("city.1", theme = "city"),
        lesson("city.2", theme = "city"),
        lesson("family.1", theme = "family"),
        lesson("family.2", theme = "family"),
        lesson("food.1", theme = "food"),
    )
    private val themeOrder = listOf("family", "travel", "city", "food")

    @Test
    fun `the block order follows the manifest and appends unknown themes`() {
        val withExtra = catalog + lesson("airport.1", theme = "airport")
        assertEquals(listOf("family", "city", "food", "airport"), CourseOrder.blockOrder(withExtra, themeOrder))
    }

    @Test
    fun `the first lesson is the first theme in teaching order`() {
        assertEquals("family.1", CourseOrder.firstLesson(catalog, themeOrder)?.id)
        assertNull(CourseOrder.firstLesson(emptyList(), themeOrder))
    }

    @Test
    fun `inside a block a dialogue comes before a story and an article, like the list`() {
        val block = listOf(
            LessonModel(id = "family.a", theme = "family", title = "article", articleId = "x"),
            LessonModel(id = "family.b", theme = "family", title = "story", storyId = "x"),
            LessonModel(id = "family.c", theme = "family", title = "dialogue", dialogueId = "x"),
        )

        assertEquals(listOf("family.c", "family.b", "family.a"), CourseOrder.teachingOrder(block, themeOrder).map { it.id })
        assertEquals("family.c", CourseOrder.firstLesson(block, themeOrder)?.id)
        assertEquals("family.b", CourseOrder.nextLesson(block, themeOrder, listOf("family.c"))?.id)
    }

    @Test
    fun `a new learner gets the first teaching lesson, not the server's first lesson`() {
        val plan = todayPlan(catalog, themeOrder, resumeLessonId = null, resumeInProgress = false)

        assertEquals("family.1", plan.lesson?.id)
        assertFalse(plan.resumable)
    }

    @Test
    fun `every level is offered on Today, A2 included`() {
        val onlyA2 = listOf(lesson("a2.city.1", level = "A2", theme = "city"))

        assertEquals("a2.city.1", todayPlan(onlyA2, themeOrder, resumeLessonId = null, resumeInProgress = false).lesson?.id)
    }

    @Test
    fun `a lesson in progress is resumed`() {
        val plan = todayPlan(catalog, themeOrder, resumeLessonId = "city.1", resumeInProgress = true)

        assertEquals("city.1", plan.lesson?.id)
        assertTrue(plan.resumable)
    }

    @Test
    fun `a resume target already at the summary falls back to the first teaching lesson`() {
        val plan = todayPlan(catalog, themeOrder, resumeLessonId = "city.1", resumeInProgress = false)

        assertEquals("family.1", plan.lesson?.id)
        assertFalse(plan.resumable)
    }

    @Test
    fun `a finished lesson is never offered again while another one is pending`() {
        val plan = todayPlan(catalog, themeOrder, null, false, finishedLessonIds = listOf("family.1"))

        assertEquals("family.2", plan.lesson?.id)
        assertFalse(plan.resumable)
    }

    @Test
    fun `finishing a whole block moves Today to the next block in teaching order`() {
        val plan = todayPlan(catalog, themeOrder, null, false, finishedLessonIds = listOf("family.1", "family.2"))

        assertEquals("city.1", plan.lesson?.id)
    }

    @Test
    fun `the lesson in progress wins over the next unfinished one`() {
        val plan = todayPlan(catalog, themeOrder, "food.1", true, finishedLessonIds = listOf("family.1"))

        assertEquals("food.1", plan.lesson?.id)
        assertTrue(plan.resumable)
    }

    @Test
    fun `with every lesson finished Today still offers a lesson`() {
        val plan = todayPlan(catalog, themeOrder, null, false, finishedLessonIds = catalog.map { it.id })

        assertEquals("family.1", plan.lesson?.id)
    }
}
