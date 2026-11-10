package com.glosso.app.billing

import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared period parser replaced `java.time.Period`: every period Play can
 * return, and the odd ones, must read and add up exactly as java.time did.
 */
class BillingPeriodsParityTest {

    private val inputs = listOf(
        "P1M", "P3M", "P6M", "P1Y", "P2Y", "P7D", "P14D", "P3D", "P1D", "P1W", "P2W", "P4W", "P30D",
        "P1Y2M", "P1Y2M3D", "P1Y2M3W4D", "P0D", "P0M", "P-1M", "-P1M", "-P-1M", "P+1M", "p1m", "P1m",
        "P12M", "P13M", "P31D", "P2147483647D", "P-2147483648D", "-P-2147483648D", "P306783379W",
        "P306783378W1D", "P", "PT1H", "P1", "1M", "P1.5M", "", " P1M", "P1MX", "P1M2Y", "P99999999999D",
    )

    private fun javaParts(iso: String): Triple<Int, Int, Int>? =
        runCatching { Period.parse(iso) }.getOrNull()?.let { Triple(it.years, it.months, it.days) }

    @Test
    fun `parses like java time`() {
        for (iso in inputs) {
            val shared = BillingPeriods.parse(iso)?.let { Triple(it.years, it.months, it.days) }
            val expected = if (iso.isBlank()) null else javaParts(iso)
            assertEquals(iso, expected, shared)
        }
    }

    private fun javaEnd(start: Long, iso: String): Long? {
        val period = runCatching { Period.parse(iso) }.getOrNull() ?: return null
        return runCatching {
            Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).plus(period).toInstant().toEpochMilli()
        }.getOrNull()
    }

    @Test
    fun `adds a trial like java time`() {
        val starts = listOf(
            0L, 1_759_276_800_000L, 1_706_659_200_000L, 1_706_745_599_999L, 1_709_164_800_000L,
            1_735_603_200_000L, 1_738_281_600_000L, 951_782_400_000L, -86_400_000L, -1L, 4_102_444_800_000L,
        )
        val periods = listOf("P1M", "P3M", "P1Y", "P7D", "P1W", "P1Y1M", "P1M30D", "P-1M", "-P1Y", "P12M", "P0D", "P4Y")
        for (start in starts) for (iso in periods) {
            assertEquals("$iso from $start", javaEnd(start, iso), BillingPeriods.endMillis(start, iso))
        }
    }
}
