package com.glosso.app.billing

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingPeriodsTest {

    @Test
    fun `parses Play periods into a count and a unit`() {
        assertEquals(PeriodParts(1, PeriodUnit.MONTH), BillingPeriods.parts("P1M"))
        assertEquals(PeriodParts(3, PeriodUnit.MONTH), BillingPeriods.parts("P3M"))
        assertEquals(PeriodParts(1, PeriodUnit.YEAR), BillingPeriods.parts("P1Y"))
        assertEquals(PeriodParts(1, PeriodUnit.WEEK), BillingPeriods.parts("P7D"))
        assertEquals(PeriodParts(2, PeriodUnit.WEEK), BillingPeriods.parts("P14D"))
        assertEquals(PeriodParts(1, PeriodUnit.DAY), BillingPeriods.parts("P1D"))
        assertNull(BillingPeriods.parts(null))
        assertNull(BillingPeriods.parts(""))
        assertNull(BillingPeriods.parts("no-es-un-periodo"))
    }

    @Test
    fun `trial end is purchase time plus the trial period`() {
        val end = BillingPeriods.endMillis(0L, "P1M")
        assertEquals(Instant.parse("1970-02-01T00:00:00Z").toEpochMilli(), end)
    }

    @Test
    fun `no trial period means no end`() {
        assertNull(BillingPeriods.endMillis(0L, null))
        assertNull(BillingPeriods.endMillis(0L, "not-a-period"))
    }
}
