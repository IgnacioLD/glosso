package com.glosso.engine.phonetics

/**
 * A sound as a learner of [accent] reads it. Scoring, the profile and the drills
 * are keyed by the dictionary's American-style phones, which a British textbook
 * writes differently. Where one key covers two British vowels (the /ɑ/ of "cot"
 * and "father"), both are shown.
 */
object AccentSymbols {

    private val BRITISH = mapOf(
        "ɑ" to "ɒ, ɑː",
        "ɔ" to "ɔː, ɒ",
        "i" to "iː",
        "u" to "uː",
        "oʊ" to "əʊ",
        "ɛ" to "e",
        "ɹ" to "r",
        "ɝ" to "ɜː",
        "ɚ" to "ə",
    )

    fun shown(phone: String, accent: String): String =
        if (accent == "en-GB") BRITISH[phone] ?: phone else phone
}
