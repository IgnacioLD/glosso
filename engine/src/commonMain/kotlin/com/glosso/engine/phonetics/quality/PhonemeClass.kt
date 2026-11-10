package com.glosso.engine.phonetics.quality

/**
 * Coarse, language-independent articulatory class of a model token.
 *
 * Faithful port of `phone_class` in `benchmark/phon-multilingual/common.py`
 * (PR #52): the frozen quality head was trained with these eight classes as a
 * one-hot feature, so the order of [NAMES] is part of the model contract and must
 * not change.
 */
object PhonemeClass {

    val NAMES = listOf(
        "vowel", "plosive", "fricative", "affricate",
        "nasal", "approximant", "tap", "other",
    )

    private val AFFRICATES = listOf("tʃ", "dʒ", "ts", "dz", "tɕ", "dʑ", "pf", "tɬ")

    // First-character membership mirrors the Python sets: diphthongs and
    // diacritic combinations classify by their leading phone.
    private const val VOWELS = "aeiouɑɒæɛɪɔʊʌəɐɜøœyɨʉɯɵɤɞɘɶʏ"
    private const val FRICATIVES = "fvszʃʒθðxɣχʁħʕhɦçʝɸβɬɮ"
    private const val PLOSIVES = "pbtdkɡgʔcqɟɢʈɖ"
    private const val NASALS = "mnŋɲɴɳɱ"
    private const val APPROX = "jwɹɻɥɰʋlɫɭʎ"
    private const val TRILL_TAP = "rɾɽ"

    fun of(token: String): String {
        if (token.isEmpty()) return "other"
        if (AFFRICATES.any { token == it || token.startsWith(it) }) return "affricate"
        val c = token[0]
        if (c in VOWELS) return "vowel"
        if (c in NASALS) return "nasal"
        if (c in PLOSIVES) return "plosive"
        if (c in FRICATIVES) return "fricative"
        if (c in TRILL_TAP) return "tap"
        if (c in APPROX) return "approximant"
        return "other"
    }

    /** Index of [token]'s class in [NAMES]; the head's one-hot position. */
    fun indexOf(token: String): Int = NAMES.indexOf(of(token)).coerceAtLeast(0)
}
