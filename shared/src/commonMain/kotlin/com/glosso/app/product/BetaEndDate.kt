package com.glosso.app.product

import com.glosso.app.platform.formatLongUtcDate

/**
 * Formats the beta end date for the UI straight from `beta.endDate` (ISO-8601
 * UTC). It is the only place the date exists as text, so the copy can never
 * drift from the server. Formatting is done in UTC on purpose: an end of
 * `2026-12-31T23:59:59Z` must read as December 31 everywhere, not slip to the
 * next day in a positive timezone. Null in, null out.
 */
fun formatBetaEndDate(iso: String?): String? {
    val value = iso?.takeIf { it.isNotBlank() } ?: return null
    return formatLongUtcDate(value)
}
