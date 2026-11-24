package com.glosso.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedClockTest {

    private val day = 24L * 60 * 60 * 1000
    private val realNow = 1_795_000_000_000L // a date in 2026
    private val year2100 = 4_102_444_800_000L

    @Test
    fun `an anchored time follows the monotonic clock whatever the device clock says`() {
        val now = TrustedClock.trustedNow(
            anchorServer = realNow, anchorMonotonic = 1_000, monotonic = 1_000 + day,
            device = year2100, floor = realNow, certificateDateFailure = false,
        )
        assertEquals(realNow + day, now)
    }

    @Test
    fun `without an anchor the day never goes back before the last trusted time`() {
        val now = TrustedClock.trustedNow(
            anchorServer = null, anchorMonotonic = null, monotonic = 5,
            device = realNow - 30 * day, floor = realNow, certificateDateFailure = false,
        )
        assertEquals(realNow, now)
    }

    @Test
    fun `without an anchor a sane device clock is used`() {
        val now = TrustedClock.trustedNow(null, null, 5, realNow + day, realNow, false)
        assertEquals(realNow + day, now)
    }

    @Test
    fun `a certificate rejected on its dates freezes time at the last trusted moment`() {
        assertEquals(realNow, TrustedClock.trustedNow(null, null, 5, year2100, realNow, true))
        // A first run with no trusted time yet can only use the device clock.
        assertEquals(year2100, TrustedClock.trustedNow(null, null, 5, year2100, 0, true))
    }

    @Test
    fun `the clock looks wrong when it is a day away from the anchor or behind the floor`() {
        assertTrue(TrustedClock.clockLooksWrong(realNow, 0, 0, year2100, realNow, false))
        assertFalse(TrustedClock.clockLooksWrong(realNow, 0, 0, realNow + 60_000, realNow, false))
        assertTrue(TrustedClock.clockLooksWrong(null, null, 0, realNow - 2 * day, realNow, false))
        assertFalse(TrustedClock.clockLooksWrong(null, null, 0, realNow + 2 * day, realNow, false))
        assertTrue(TrustedClock.clockLooksWrong(null, null, 0, realNow, realNow, true))
    }

    @Test
    fun `certificate date failures are recognised through the cause chain`() {
        val jvm = RuntimeException(
            "Chain validation failed",
            RuntimeException("timestamp check failed", RuntimeException("Certificate expired at Sat Feb 13 2027")),
        )
        assertTrue(TrustedClock.isCertificateDateFailure(jvm))
        assertTrue(TrustedClock.isCertificateDateFailure(RuntimeException("NSURLErrorDomain Code=-1204")))
        assertFalse(TrustedClock.isCertificateDateFailure(RuntimeException("Unable to resolve host")))
    }
}
