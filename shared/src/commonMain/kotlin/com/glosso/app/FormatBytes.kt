package com.glosso.app

import com.glosso.app.platform.formatDecimal
import com.glosso.app.platform.localeLanguage

/**
 * Human size, e.g. "1.4 MB", rendered in the locale [languageTag] names (the
 * device's default when null) so the decimal separator and the unit read right
 * ("1,4 Mo" in French) instead of always using an American dot. Shared so every
 * surface reads the same.
 */
fun formatBytes(bytes: Long, languageTag: String? = null): String = when {
    bytes >= 1024L * 1024L * 1024L -> sizeText(bytes / 1024.0 / 1024.0 / 1024.0, 1, SizeUnit.GIGA, languageTag)
    bytes >= 1024L * 1024L -> sizeText(bytes / 1024.0 / 1024.0, 1, SizeUnit.MEGA, languageTag)
    bytes >= 1024L -> sizeText(bytes / 1024.0, 0, SizeUnit.KILO, languageTag)
    else -> "$bytes B"
}

private enum class SizeUnit { KILO, MEGA, GIGA }

private fun sizeText(value: Double, decimals: Int, unit: SizeUnit, languageTag: String?): String {
    val number = formatDecimal(value, decimals, languageTag)
    // French names the octet units (Ko, Mo, Go); the others use the byte symbols.
    val french = localeLanguage(languageTag) == "fr"
    val label = when (unit) {
        SizeUnit.KILO -> if (french) "Ko" else "KB"
        SizeUnit.MEGA -> if (french) "Mo" else "MB"
        SizeUnit.GIGA -> if (french) "Go" else "GB"
    }
    return "$number $label"
}
