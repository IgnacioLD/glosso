package com.glosso.app

import com.glosso.app.platform.todayEpochDay

/** The daily-streak arithmetic, pure so the rollover is tested without a clock. */
object DailyStreak {

    /**
     * The streak after a scored take on [today]: unchanged if already counted
     * today, one more if the last take was yesterday, and back to one after a
     * gap. Never invents consecutive days.
     */
    fun next(lastTakeDay: Long, current: Int, today: Long): Int = when (lastTakeDay) {
        today -> current.coerceAtLeast(1)
        today - 1 -> current + 1
        else -> 1
    }

    /**
     * The streak to show as of [today]: the stored value only while the last
     * scored take was today or yesterday. After a longer gap the run is already
     * broken, even though nothing has recomputed it yet, so the chip says zero
     * instead of a stale number. Pure, so the rollover is tested without a clock.
     */
    fun displayed(lastTakeDay: Long, current: Int, today: Long): Int =
        if (lastTakeDay != 0L && today in lastTakeDay..(lastTakeDay + 1)) current else 0
}

/** The current daily streak of this progress as of [today]. */
fun ProgressData.shownDailyStreak(today: Long = todayEpochDay()): Int =
    DailyStreak.displayed(lastTakeDay, dailyStreak, today)
