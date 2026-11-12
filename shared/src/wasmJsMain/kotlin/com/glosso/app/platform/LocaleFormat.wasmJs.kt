package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Intl in UTC on the epoch day's midnight, so the day never shifts.
actual fun formatDecimal(value: Double, decimals: Int, languageTag: String?): String =
    intlDecimal(value, decimals, languageTag.orEmpty())

actual fun localeLanguage(languageTag: String?): String = intlLanguage(languageTag.orEmpty())

actual fun formatWeekdayDayMonth(epochDay: Long, languageTag: String): String =
    intlDate(epochDay.toDouble(), languageTag, "{\"weekday\":\"long\",\"day\":\"numeric\",\"month\":\"long\"}")

actual fun formatDayShortMonth(epochDay: Long): String =
    intlDate(epochDay.toDouble(), "", "{\"day\":\"numeric\",\"month\":\"short\"}")

// 1970-01-05 (epoch day 4) was a Monday; the app counts weeks from Monday.
actual fun narrowWeekdayNames(): List<String> =
    (4L..10L).map { intlDate(it.toDouble(), "", "{\"weekday\":\"narrow\"}") }

@Composable
actual fun currentLocaleTag(): String = remember { navigatorLanguage() }

private fun intlDecimal(value: Double, decimals: Int, tag: String): String = js(
    """new Intl.NumberFormat(tag || undefined, {
        minimumFractionDigits: decimals, maximumFractionDigits: decimals,
        useGrouping: false, roundingMode: 'halfEven' }).format(value)""",
)

private fun intlLanguage(tag: String): String = js("new Intl.Locale(tag || navigator.language || 'en').language")

private fun intlDate(epochDay: Double, tag: String, options: String): String = js(
    "new Intl.DateTimeFormat(tag || undefined, Object.assign({ timeZone: 'UTC' }, JSON.parse(options))).format(new Date(epochDay * 86400000))",
)

internal fun navigatorLanguage(): String = js("navigator.language || 'en'")
