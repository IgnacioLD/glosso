package com.glosso.app.platform

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private fun localeOf(languageTag: String?): Locale =
    languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()

actual fun formatDecimal(value: Double, decimals: Int, languageTag: String?): String =
    NumberFormat.getNumberInstance(localeOf(languageTag)).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
        isGroupingUsed = false
    }.format(value)

actual fun localeLanguage(languageTag: String?): String = localeOf(languageTag).language

actual fun formatLongUtcDate(iso: String): String? = runCatching {
    val instant = Instant.parse(iso)
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
    instant.atZone(ZoneOffset.UTC).toLocalDate().format(formatter)
}.getOrNull()

actual fun formatWeekdayDayMonth(epochDay: Long, languageTag: String): String {
    val locale = Locale.forLanguageTag(languageTag)
    return LocalDate.ofEpochDay(epochDay)
        .format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale))
}

actual fun formatDayShortMonth(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("d MMM"))

actual fun narrowWeekdayNames(): List<String> =
    (1..7).map { day -> DayOfWeek.of(day).getDisplayName(TextStyle.NARROW, Locale.getDefault()) }

@Composable
actual fun currentLocaleTag(): String = LocalConfiguration.current.locales[0].toLanguageTag()
