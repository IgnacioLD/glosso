package com.glosso.app

import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhonemeHistoryTest {

    private val profile = PhoneticProfileModel(
        stats = listOf(
            PhonemeStatModel("θ", attempts = 4, correct = 1),
            PhonemeStatModel("r", attempts = 2, correct = 2),
        ),
    )

    @Test
    fun `the week starts on Monday`() {
        // 2026-09-23 is a Wednesday (epoch day 20719).
        val wednesday = 20_719L
        val monday = PhonemeHistory.weekStartOf(wednesday)
        assertEquals(20_717L, monday)
        assertEquals(4L, PhonemeHistory.weekStartOf(4L)) // 1970-01-05, a Monday.
        assertEquals(-3L, PhonemeHistory.weekStartOf(0L)) // 1970-01-01 (Thu) -> Mon 1969-12-29.
    }

    @Test
    fun `the first take of the week captures a baseline`() {
        val captured = PhonemeHistory.capture(existing = null, weekStart = 100L, profile = profile)

        assertEquals(100L, captured?.weekStart)
        assertEquals(50, captured?.overall)
        assertEquals(25, captured?.sounds?.get("θ"))
        assertEquals(100, captured?.sounds?.get("r"))
    }

    @Test
    fun `an existing baseline for this week is kept`() {
        val existing = WeeklyBaseline(weekStart = 100L, overall = 10, sounds = mapOf("θ" to 10))
        assertNull(PhonemeHistory.capture(existing, weekStart = 100L, profile = profile))
    }

    @Test
    fun `a new week replaces the baseline`() {
        val existing = WeeklyBaseline(weekStart = 93L, overall = 10)
        val captured = PhonemeHistory.capture(existing, weekStart = 100L, profile = profile)
        assertEquals(100L, captured?.weekStart)
        assertEquals(50, captured?.overall)
    }

    @Test
    fun `an empty profile captures nothing`() {
        assertNull(PhonemeHistory.capture(null, weekStart = 100L, profile = null))
    }

    @Test
    fun `baseline lookup ignores vowel length`() {
        val baseline = WeeklyBaseline(weekStart = 100L, overall = 30, sounds = mapOf("i" to 30))
        assertEquals(30, PhonemeHistory.baselineSound(baseline, "iː"))
    }
}
