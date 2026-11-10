package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeObservationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhonemeSchedulerTest {

    private fun obs(day: Long, accuracy: Int, phoneme: String = "θ", error: String? = "s") =
        PhonemeObservationModel(
            phoneme = phoneme,
            day = day,
            total = 100,
            correct = accuracy,
            error = if (accuracy == 100) null else error,
        )

    @Test
    fun `a sound with no history is due for a baseline`() {
        val schedule = PhonemeScheduler.schedule(emptyList(), "θ", today = 50)
        assertEquals(ReviewStatus.DUE, schedule.status)
        assertEquals(ReviewReason.NEW, schedule.reason)
        assertEquals(50, schedule.dueDay)
    }

    @Test
    fun `an improving sound earns a longer interval`() {
        val history = (0..8L).map { day ->
            obs(day = day, accuracy = (40 + day * 5).toInt().coerceAtMost(90))
        }
        val schedule = PhonemeScheduler.schedule(history, "θ", today = 8)
        // 9 distinct days of steady improvement: a long leash, not daily drills.
        assertTrue(schedule.intervalDays >= 6, "improving sound should be spaced out")
        assertTrue(schedule.status != ReviewStatus.DUE)
    }

    @Test
    fun `a declining sound is reactivated daily`() {
        val history = listOf(obs(1, 80), obs(3, 60), obs(7, 45), obs(10, 30))
        val schedule = PhonemeScheduler.schedule(history, "θ", today = 11)
        assertEquals(ReviewReason.LAPSED, schedule.reason)
        assertEquals(PhonemeScheduler.MIN_INTERVAL, schedule.intervalDays)
        assertEquals(ReviewStatus.DUE, schedule.status)
    }

    @Test
    fun `a confident pattern keeps the sound due every day`() {
        val history = (1..5L).map { obs(day = it, accuracy = 30) }
        val schedule = PhonemeScheduler.schedule(history, "θ", today = 6)
        assertEquals(ReviewReason.PERSISTENT, schedule.reason)
        assertEquals(PhonemeScheduler.MIN_INTERVAL, schedule.intervalDays)
        assertEquals(ReviewStatus.DUE, schedule.status)
    }

    @Test
    fun `due lists only the sounds owed today`() {
        val history = listOf(
            obs(1, 90, phoneme = "r"),
            obs(1, 30, phoneme = "θ"),
        )
        val due = PhonemeScheduler.due(history, today = 10)
        assertTrue(due.any { it.phoneme == "θ" })
    }

    @Test
    fun `the interval starts at the default and is capped at the maximum`() {
        assertEquals(PhonemeScheduler.DEFAULT_INTERVAL, PhonemeScheduler.nextInterval(0))
        assertEquals(PhonemeScheduler.MAX_INTERVAL, PhonemeScheduler.nextInterval(30))
    }
}
