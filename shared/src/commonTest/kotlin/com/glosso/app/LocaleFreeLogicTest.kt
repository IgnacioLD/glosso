package com.glosso.app

import com.glosso.app.billing.BillingPeriods
import com.glosso.app.billing.PeriodParts
import com.glosso.app.billing.PeriodUnit
import com.glosso.app.platform.isoDayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The platform-free pieces the shared UI computes itself, on every target. */
class LocaleFreeLogicTest {

    @Test
    fun billingPeriodsReadPlayPeriods() {
        assertEquals(PeriodParts(1, PeriodUnit.MONTH), BillingPeriods.parts("P1M"))
        assertEquals(PeriodParts(1, PeriodUnit.YEAR), BillingPeriods.parts("P1Y"))
        assertEquals(PeriodParts(2, PeriodUnit.WEEK), BillingPeriods.parts("P2W"))
        assertEquals(PeriodParts(3, PeriodUnit.DAY), BillingPeriods.parts("P3D"))
        assertNull(BillingPeriods.parts("PT1H"))
        assertNull(BillingPeriods.parts(null))
    }

    @Test
    fun aTrialEndsAfterItsCalendarPeriodInUtc() {
        // 2024-01-31 + 1 month clamps to 2024-02-29 (leap year), at the same time of day.
        val jan31 = 1_706_659_200_000L + 3_600_000L
        assertEquals(1_709_164_800_000L + 3_600_000L, BillingPeriods.endMillis(jan31, "P1M"))
        assertEquals(31L * 86_400_000L, BillingPeriods.endMillis(0L, "P1M"))
        assertNull(BillingPeriods.endMillis(0L, null))
    }

    @Test
    fun isoDayOfWeekCountsFromMonday() {
        assertEquals(4, isoDayOfWeek(0)) // 1970-01-01, a Thursday
        assertEquals(3, isoDayOfWeek(-1))
        assertEquals(1, isoDayOfWeek(20_346)) // 2025-09-15, a Monday
        assertEquals(7, isoDayOfWeek(20_352)) // 2025-09-21, a Sunday
    }
}
