package com.glosso.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DailyStreakTest {

    private val today = 20_000L

    @Test
    fun `the first take starts the streak at one`() {
        assertEquals(1, DailyStreak.next(lastTakeDay = 0, current = 0, today = today))
    }

    @Test
    fun `a second take the same day does not inflate the streak`() {
        assertEquals(3, DailyStreak.next(lastTakeDay = today, current = 3, today = today))
    }

    @Test
    fun `a take yesterday extends the streak`() {
        assertEquals(4, DailyStreak.next(lastTakeDay = today - 1, current = 3, today = today))
    }

    @Test
    fun `a gap resets the streak to one`() {
        assertEquals(1, DailyStreak.next(lastTakeDay = today - 3, current = 8, today = today))
    }

    @Test
    fun `the shown streak stays while today counts`() {
        assertEquals(3, DailyStreak.displayed(lastTakeDay = today, current = 3, today = today))
    }

    @Test
    fun `the shown streak stays through yesterday`() {
        assertEquals(3, DailyStreak.displayed(lastTakeDay = today - 1, current = 3, today = today))
    }

    @Test
    fun `the shown streak is zero once the run is already broken`() {
        // The stored value is still 8, but the last take was three days ago, so
        // the chip must not keep claiming a run that is gone.
        assertEquals(0, DailyStreak.displayed(lastTakeDay = today - 3, current = 8, today = today))
    }

    @Test
    fun `a learner who never practised shows zero`() {
        assertEquals(0, DailyStreak.displayed(lastTakeDay = 0, current = 0, today = today))
    }

    @Test
    fun `an unstamped day never passes a stale run as fresh`() {
        // A caller that forgets to stamp the day must not keep a broken run alive.
        assertEquals(
            0,
            DailyStreak.displayed(lastTakeDay = today, current = 8, today = AchievementSignals.UNSTAMPED),
        )
    }
}
