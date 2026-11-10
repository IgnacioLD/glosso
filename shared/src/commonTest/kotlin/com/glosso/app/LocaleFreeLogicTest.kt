package com.glosso.app

import com.glosso.app.platform.isoDayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals

/** The platform-free pieces the shared UI computes itself, on every target. */
class LocaleFreeLogicTest {

    @Test
    fun isoDayOfWeekCountsFromMonday() {
        assertEquals(4, isoDayOfWeek(0)) // 1970-01-01, a Thursday
        assertEquals(3, isoDayOfWeek(-1))
        assertEquals(1, isoDayOfWeek(20_346)) // 2025-09-15, a Monday
        assertEquals(7, isoDayOfWeek(20_352)) // 2025-09-21, a Sunday
    }
}
