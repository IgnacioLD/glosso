package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Instant
import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterLongStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfEven
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.languageCode
import platform.Foundation.localeIdentifier
import platform.Foundation.timeZoneForSecondsFromGMT

private const val SECONDS_PER_DAY = 86_400.0

private fun localeOf(languageTag: String?): NSLocale =
    languageTag?.let { NSLocale(localeIdentifier = it.replace('-', '_')) } ?: NSLocale.currentLocale

private val utc: NSTimeZone get() = NSTimeZone.timeZoneForSecondsFromGMT(0)

/** Midnight UTC of [epochDay], formatted in UTC so the day never shifts. */
private fun dateOf(epochDay: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(epochDay * SECONDS_PER_DAY)

private fun utcFormatter(locale: NSLocale): NSDateFormatter = NSDateFormatter().apply {
    this.locale = locale
    timeZone = utc
    calendar = NSCalendar(calendarIdentifier = platform.Foundation.NSCalendarIdentifierGregorian)
}

actual fun formatDecimal(value: Double, decimals: Int, languageTag: String?): String =
    NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        locale = localeOf(languageTag)
        minimumFractionDigits = decimals.toULong()
        maximumFractionDigits = decimals.toULong()
        usesGroupingSeparator = false
        roundingMode = NSNumberFormatterRoundHalfEven
    }.stringFromNumber(NSNumber(double = value)) ?: value.toString()

actual fun localeLanguage(languageTag: String?): String =
    localeOf(languageTag).languageCode

actual fun formatLongUtcDate(iso: String): String? {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val epochDay = instant.epochSeconds.floorDiv(86_400L)
    return utcFormatter(NSLocale.currentLocale).apply {
        dateStyle = NSDateFormatterLongStyle
        timeStyle = NSDateFormatterNoStyle
    }.stringFromDate(dateOf(epochDay))
}

actual fun formatWeekdayDayMonth(epochDay: Long, languageTag: String): String {
    val locale = localeOf(languageTag)
    val pattern = NSDateFormatter.dateFormatFromTemplate("EEEEdMMMM", 0u, locale) ?: "EEEE d MMMM"
    return utcFormatter(locale).apply { dateFormat = pattern }.stringFromDate(dateOf(epochDay))
}

actual fun formatDayShortMonth(epochDay: Long): String =
    utcFormatter(NSLocale.currentLocale).apply { dateFormat = "d MMM" }.stringFromDate(dateOf(epochDay))

@Suppress("UNCHECKED_CAST")
actual fun narrowWeekdayNames(): List<String> {
    // Foundation lists them Sunday first; the app counts weeks from Monday.
    val sundayFirst = utcFormatter(NSLocale.currentLocale).veryShortWeekdaySymbols as List<String>
    return sundayFirst.drop(1) + sundayFirst.take(1)
}

@Composable
actual fun currentLocaleTag(): String = remember { NSLocale.currentLocale.localeIdentifier.replace('_', '-') }
