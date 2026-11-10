package com.glosso.app.billing

import com.glosso.engine.model.LessonModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayPlanTest {

    private fun lesson(id: String, level: String = "A1", theme: String = "city") =
        LessonModel(id = id, level = level, theme = theme, title = id)

    // The catalog arrives in server order: the free block (family) is NOT first,
    // exactly like production, where the first lesson the server returns is a Pro
    // one. Only the teaching order can pick the free lesson.
    private val catalog = listOf(
        lesson("city.1", theme = "city"),
        lesson("city.2", theme = "city"),
        lesson("family.1", theme = "family"),
        lesson("family.2", theme = "family"),
        lesson("food.1", theme = "food"),
    )
    private val themeOrder = listOf("family", "travel", "city", "food")
    private val free = catalog.first { it.id == "family.1" }
    private val paid = catalog.first { it.id == "city.1" }

    @Test
    fun `a new free learner gets the free first block, not the server's first lesson`() {
        val plan = todayPlan(catalog, themeOrder, resumeLessonId = null, resumeInProgress = false, status = SubscriptionStatus.NONE)

        // The old firstOrNull() opened city.1, which is Pro. The gate must land on
        // the same free lesson the lesson list shows.
        assertEquals("family.1", plan.lesson?.id)
        assertFalse(plan.resumable)
        assertFalse(plan.locked)
    }

    @Test
    fun `a free learner whose next lesson is Pro gets the paywall, not the lesson`() {
        val onlyA2 = listOf(lesson("a2.city.1", level = "A2", theme = "city"))

        val plan = todayPlan(onlyA2, themeOrder, resumeLessonId = null, resumeInProgress = false, status = SubscriptionStatus.NONE)

        assertTrue(plan.locked)
    }

    @Test
    fun `an entitled learner is never locked on Today`() {
        val onlyA2 = listOf(lesson("a2.city.1", level = "A2", theme = "city"))

        for (status in listOf(SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE)) {
            val plan = todayPlan(onlyA2, themeOrder, resumeLessonId = null, resumeInProgress = false, status = status)
            assertFalse("locked for $status", plan.locked)
        }
    }

    @Test
    fun `an in-progress Pro lesson is resumed, never paywalled`() {
        val plan = todayPlan(catalog, themeOrder, resumeLessonId = paid.id, resumeInProgress = true, status = SubscriptionStatus.NONE)

        assertEquals(paid.id, plan.lesson?.id)
        assertTrue(plan.resumable)
        assertFalse(plan.locked)
    }

    @Test
    fun `a resume target already at the summary falls back to the first teaching lesson`() {
        val plan = todayPlan(catalog, themeOrder, resumeLessonId = paid.id, resumeInProgress = false, status = SubscriptionStatus.NONE)

        assertEquals(free.id, plan.lesson?.id)
        assertFalse(plan.resumable)
    }

    @Test
    fun `a finished lesson is never offered again while another one is pending`() {
        val plan = todayPlan(
            catalog,
            themeOrder,
            resumeLessonId = null,
            resumeInProgress = false,
            status = SubscriptionStatus.NONE,
            finishedLessonIds = listOf("family.1"),
        )

        // The bug: with nothing in progress Today fell back to family.1 forever,
        // so the learner was always "on the first lesson".
        assertEquals("family.2", plan.lesson?.id)
        assertFalse(plan.resumable)
    }

    @Test
    fun `finishing a whole block moves Today to the next block in teaching order`() {
        val plan = todayPlan(
            catalog,
            themeOrder,
            resumeLessonId = null,
            resumeInProgress = false,
            status = SubscriptionStatus.TRIAL,
            finishedLessonIds = listOf("family.1", "family.2"),
        )

        assertEquals("city.1", plan.lesson?.id)
    }

    @Test
    fun `the lesson in progress wins over the next unfinished one`() {
        val plan = todayPlan(
            catalog,
            themeOrder,
            resumeLessonId = "food.1",
            resumeInProgress = true,
            status = SubscriptionStatus.TRIAL,
            finishedLessonIds = listOf("family.1"),
        )

        assertEquals("food.1", plan.lesson?.id)
        assertTrue(plan.resumable)
    }

    @Test
    fun `with every lesson finished Today still offers a lesson`() {
        val plan = todayPlan(
            catalog,
            themeOrder,
            resumeLessonId = null,
            resumeInProgress = false,
            status = SubscriptionStatus.TRIAL,
            finishedLessonIds = catalog.map { it.id },
        )

        assertEquals("family.1", plan.lesson?.id)
    }
}
