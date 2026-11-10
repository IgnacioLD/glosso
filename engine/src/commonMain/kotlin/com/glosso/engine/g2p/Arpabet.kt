package com.glosso.engine.g2p

/**
 * ARPAbet (CMUdict) to IPA.
 *
 * Stress digits on vowels become IPA stress marks: `1` primary (ˈ), `2` secondary
 * (ˌ), `0` unstressed (no mark). The schwa and rhotacized schwa are the unstressed
 * forms of AH and ER.
 *
 * NOTE (F1): the exact symbols must be validated against the acoustic model's
 * vocabulary, which carries allophones this table does not model. Treat the output
 * as a starting point, not ground truth.
 */
object Arpabet {

    private val baseToIpa = mapOf(
        "AA" to "ɑ", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔ", "AW" to "aʊ",
        "AY" to "aɪ", "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð",
        "EH" to "ɛ", "ER" to "ɝ", "EY" to "eɪ", "F" to "f", "G" to "ɡ",
        "HH" to "h", "IH" to "ɪ", "IY" to "i", "JH" to "dʒ", "K" to "k",
        "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ", "OW" to "oʊ",
        "OY" to "ɔɪ", "P" to "p", "R" to "ɹ", "S" to "s", "SH" to "ʃ",
        "T" to "t", "TH" to "θ", "UH" to "ʊ", "UW" to "u", "V" to "v",
        "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ",
    )

    /** Converts a single ARPAbet token (e.g. `AE1`, `T`) to IPA, or null if unknown. */
    fun phoneme(token: String): String? {
        val upper = token.uppercase()
        val stress = upper.lastOrNull()?.takeIf { it.isDigit() }
        val base = if (stress != null) upper.dropLast(1) else upper

        val ipa = when {
            base == "AH" && stress == '0' -> "ə"
            base == "ER" && stress == '0' -> "ɚ"
            else -> baseToIpa[base] ?: return null
        }

        return when (stress) {
            '1' -> "ˈ$ipa"
            '2' -> "ˌ$ipa"
            else -> ipa
        }
    }

    /** Converts a whitespace-separated ARPAbet pronunciation to IPA. */
    fun word(arpabet: String): String = arpabet
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .mapNotNull { phoneme(it) }
        .joinToString("")
}
