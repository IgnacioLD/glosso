package com.glosso.app.platform

import androidx.compose.runtime.Composable

/*
 * Locale-aware text the platform formats: numbers and dates read the way the
 * learner's language writes them. Android keeps `java.text`/`java.time` (the
 * text it has always shown); iOS uses `NSNumberFormatter`/`NSDateFormatter`.
 * A null `languageTag` means the device's default locale.
 */

/** [value] with exactly [decimals] fraction digits and no grouping, rounded half-even. */
expect fun formatDecimal(value: Double, decimals: Int, languageTag: String? = null): String

/** The language of [languageTag] ("fr" for "fr-CA"), or of the default locale. */
expect fun localeLanguage(languageTag: String? = null): String

/**
 * The long, localized date of an ISO-8601 instant read in UTC ("December 31,
 * 2027", "31 de diciembre de 2027"), or null when [iso] is not one.
 */
expect fun formatLongUtcDate(iso: String): String?

/**
 * Weekday, day and month of [epochDay] in the order [languageTag] writes them
 * ("Friday, September 25", "viernes, 25 de septiembre").
 */
expect fun formatWeekdayDayMonth(epochDay: Long, languageTag: String): String

/** Day and abbreviated month of [epochDay] ("5 Mar"), in the default locale. */
expect fun formatDayShortMonth(epochDay: Long): String

/** The one-letter weekday names, Monday first, in the default locale. */
expect fun narrowWeekdayNames(): List<String>

/** The composition's locale as a BCP 47 tag (the configuration's first locale on Android). */
@Composable
expect fun currentLocaleTag(): String

/** ISO day of the week of [epochDay]: 1 is Monday, 7 is Sunday (1970-01-01 was a Thursday). */
fun isoDayOfWeek(epochDay: Long): Int = (epochDay + 3).mod(7) + 1
