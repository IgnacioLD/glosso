package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReminderTest {

    private val today = 20_000L

    @Test
    fun `a learner who never recorded has no days since`() {
        assertNull(daysSinceLastActive(lastActiveDay = 0, today = today))
    }

    @Test
    fun `a take today reads as zero days`() {
        assertEquals(0, daysSinceLastActive(lastActiveDay = today, today = today))
    }

    @Test
    fun `a take three days ago reads as three`() {
        assertEquals(3, daysSinceLastActive(lastActiveDay = today - 3, today = today))
    }

    @Test
    fun `a clock that jumps backwards never reads as negative`() {
        assertEquals(0, daysSinceLastActive(lastActiveDay = today + 5, today = today))
    }

    @Test
    fun `the nudge waits until the threshold, not the first missed day`() {
        assertTrue(REMINDER_AFTER_DAYS > 1)
    }
}
