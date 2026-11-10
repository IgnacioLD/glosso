package com.glosso.engine.phonetics

/**
 * The phone classes the prosody layer needs to know about. Kept in one place so
 * "is this a vowel?" cannot drift between stress parsing and stress scoring.
 */
object PhoneticInventory {

    private val VOWELS = setOf(
        "i", "ɪ", "e", "ɛ", "æ", "a", "ɑ", "ɒ", "ɔ", "ʌ", "ə", "ɐ", "ɜ",
        "ʊ", "u", "o", "ɵ", "ø", "œ", "y", "ʏ", "ɨ", "ɯ", "ɤ", "ɚ", "ɝ",
    )

    /** True for a vowel or diphthong element; the length mark is ignored. */
    fun isVowel(phone: String): Boolean = phone.removeSuffix("ː") in VOWELS
}
