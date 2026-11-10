package com.glosso.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformTest {

    @Test
    fun `today is the epoch day of now give or take the time zone`() {
        val utcDay = currentTimeMillis() / 86_400_000L
        assertTrue(todayEpochDay() in (utcDay - 1)..(utcDay + 1))
    }

    @Test
    fun `a lock returns the block's value and can be taken again inside`() {
        val lock = Lock()
        assertEquals(3, lock.withLock { lock.withLock { 1 + 2 } })
    }
}
