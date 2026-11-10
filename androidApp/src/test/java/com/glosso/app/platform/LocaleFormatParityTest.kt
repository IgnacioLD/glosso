package com.glosso.app.platform

import android.text.format.DateFormat
import com.glosso.app.formatBytes
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The locale-aware text moved to the shared code must read exactly as the
 * Android app wrote it before: each check compares the shared function with a
 * copy of the code it replaced (java.text / java.time), in English, Spanish,
 * French and German and a few regional variants, plus fixed strings so a JDK
 * change cannot hide a drift on both sides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocaleFormatParityTest {

    private val original = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(original)

    private val locales = listOf("en", "en-US", "en-GB", "es", "es-ES", "es-MX", "fr", "fr-FR", "fr-CA", "de", "de-DE", "de-AT")

    private val sizes = listOf(
        0L, 1L, 512L, 1023L, 1024L, 1536L, 2048L, 10_239L, 10_240L, 524_288L, 1_048_575L, 1_048_576L,
        1_101_005L, 1_572_864L, 1_258_291L, 1_310_720L, 3_407_872L, 52_428_800L, 104_857_599L,
        358_357_774L, 1_073_741_823L, 1_073_741_824L, 1_342_177_280L, 5_905_580_032L,
    )

    /** `formatBytes` as the Android app had it (FormatBytes.kt before the move). */
    private fun legacyFormatBytes(bytes: Long, locale: Locale): String {
        fun text(value: Double, decimals: Int, kilo: String, french: String): String {
            val number = NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = decimals
                maximumFractionDigits = decimals
                isGroupingUsed = false
            }.format(value)
            return "$number ${if (locale.language == "fr") french else kilo}"
        }
        return when {
            bytes >= 1024L * 1024L * 1024L -> text(bytes / 1024.0 / 1024.0 / 1024.0, 1, "GB", "Go")
            bytes >= 1024L * 1024L -> text(bytes / 1024.0 / 1024.0, 1, "MB", "Mo")
            bytes >= 1024L -> text(bytes / 1024.0, 0, "KB", "Ko")
            else -> "$bytes B"
        }
    }

    @Test
    fun `formatBytes reads as before in every app language`() {
        for (tag in locales) {
            val locale = Locale.forLanguageTag(tag)
            for (bytes in sizes) {
                assertEquals("$bytes in $tag (explicit)", legacyFormatBytes(bytes, locale), formatBytes(bytes, tag))
                Locale.setDefault(locale)
                assertEquals("$bytes in $tag (default)", legacyFormatBytes(bytes, locale), formatBytes(bytes))
            }
        }
    }

    @Test
    fun `formatBytes fixed strings`() {
        assertEquals("341.8 MB", formatBytes(358_357_774L, "en"))
        assertEquals("341,8 MB", formatBytes(358_357_774L, "es"))
        assertEquals("341,8 Mo", formatBytes(358_357_774L, "fr"))
        assertEquals("341,8 MB", formatBytes(358_357_774L, "de"))
        assertEquals("1.2 MB", formatBytes(1_258_291L, "en"))
        assertEquals("1,2 MB", formatBytes(1_310_720L, "de"))
        assertEquals("1024 KB", formatBytes(1_048_575L, "en"))
        // 1.25 GiB: half-even rounding, as java.text always did.
        assertEquals("1,2 Go", formatBytes(1_342_177_280L, "fr"))
        assertEquals("512 B", formatBytes(512L, "es"))
    }

    @Test
    fun `the Today date line reads as before`() {
        val days = listOf(LocalDate.of(2026, 9, 25), LocalDate.of(2027, 1, 1), LocalDate.of(2028, 2, 29), LocalDate.of(2026, 12, 31))
        for (tag in locales) {
            val locale = Locale.forLanguageTag(tag)
            for (day in days) {
                val legacy = day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale))
                assertEquals("$day in $tag", legacy, formatWeekdayDayMonth(day.toEpochDay(), tag))
            }
        }
    }

    @Test
    fun `the activity chart and the week dots read as before`() {
        for (tag in locales) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            for (day in listOf(LocalDate.of(2026, 9, 7), LocalDate.of(2027, 3, 5), LocalDate.of(2026, 12, 31))) {
                assertEquals("$day in $tag", day.format(DateTimeFormatter.ofPattern("d MMM")), formatDayShortMonth(day.toEpochDay()))
            }
            val legacyInitials = (1..7).map { DayOfWeek.of(it).getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
            assertEquals(tag, legacyInitials, narrowWeekdayNames())
        }
    }

    @Test
    fun `the ISO day of the week matches java time`() {
        var day = LocalDate.of(1969, 12, 1)
        while (day.isBefore(LocalDate.of(2031, 1, 1))) {
            assertEquals(day.toString(), day.dayOfWeek.value, isoDayOfWeek(day.toEpochDay()))
            day = day.plusDays(1)
        }
    }
}
