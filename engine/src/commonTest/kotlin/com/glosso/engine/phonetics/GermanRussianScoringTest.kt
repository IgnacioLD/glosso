package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * German and Russian through the same scorer as English and French. The
 * references are what espeak-ng 1.52 writes for these words (the lexicon source);
 * the "said" side is what a learner error looks like in the model's tokens.
 *
 * These pin the tables, not recognition accuracy: no real German or Russian
 * speech has been measured yet (docs/ADD_LANGUAGE.md).
 */
class GermanRussianScoringTest {

    private fun score(text: String, expected: String, actual: String, language: String) =
        PhoneticComparator.calculateScoringResult(text, expected, actual, language)

    private fun ruleIds(result: ScoringResult, language: String): List<String?> =
        ErrorRules.analyze(result.alignment, language).map { it.ruleId }

    // region German

    @Test
    fun `german said as written scores 100`() {
        assertEquals(100, score("fünf Brüder", "fˈʏnf bɾˈyːdɜ", "fʏnf bɾyːdɜ", "de").score)
        assertEquals(100, score("Ich möchte nicht", "ɪç mˈœçtə nˈɪçt", "ɪç mœçtə nɪçt", "de-DE").score)
    }

    @Test
    fun `the model's y for espeak's short u-umlaut is the same sound`() {
        val result = score("fünf", "fˈʏnf", "fynf", "de")
        assertEquals(100, result.score)
        assertTrue(result.alignment.all { it.status == MatchStatus.PERFECT })
    }

    @Test
    fun `every german r realisation matches the espeak reference`() {
        for (r in listOf("ɾ", "r", "ʁ")) {
            assertEquals(100, score("Brot", "bɾˈoːt", "b${r}oːt", "de").score, "R said as $r")
        }
        // A vocalised coda R ("Bier" [biːɐ]) is native, not a dropped sound.
        assertEquals(100, score("Bier", "bˈiːɾ", "biːɐ", "de").score)
    }

    @Test
    fun `u-umlaut said as u scores lower and is explained`() {
        val result = score("Mütter", "mˈʏtɜ", "mʊtɜ", "de")
        assertTrue(result.score < 100, "score was ${result.score}")
        assertEquals(MatchStatus.CLOSE, result.alignment[1].status)
        assertEquals(listOf<String?>("de.ue.short.u"), ruleIds(result, "de"))

        val long = score("Brüder", "bɾˈyːdɜ", "bɾuːdɜ", "de")
        assertTrue(long.score < 100)
        assertEquals(listOf<String?>("de.ue.u"), ruleIds(long, "de"))
    }

    @Test
    fun `the ich sound said as sch or as a spanish jota is explained`() {
        val sch = score("ich", "ɪç", "ɪʃ", "de")
        assertTrue(sch.score < 100)
        assertEquals(listOf<String?>("de.ich.sch"), ruleIds(sch, "de"))

        val jota = score("ich", "ɪç", "ɪx", "de")
        assertTrue(jota.score < 100)
        assertEquals(listOf<String?>("de.ich.ach"), ruleIds(jota, "de"))
    }

    @Test
    fun `german vowel length is a contrast in the espeak notation too`() {
        // Staat said as Stadt: espeak writes the long A as ɑː, the short one as a.
        val result = score("Staat", "ʃtˈɑːt", "ʃtat", "de")
        assertTrue(result.score < 100)
        assertEquals(MatchStatus.CLOSE, result.alignment[2].status)
        assertEquals(listOf<String?>("de.long.a"), ruleIds(result, "de"))
        // The same vowel written aː by another transcription is not an error.
        assertEquals(100, score("Staat", "ʃtˈɑːt", "ʃtaːt", "de").score)
    }

    @Test
    fun `a dropped german h and a b for w are explained`() {
        val dropped = score("Haus", "hˈaʊs", "aʊs", "de")
        assertTrue(dropped.score < 100)
        assertEquals(listOf<String?>("de.h.dropping"), ruleIds(dropped, "de"))

        val merged = score("Wein", "vˈaɪn", "baɪn", "de")
        assertTrue(merged.score < 100)
        assertEquals(listOf<String?>("de.w.b"), ruleIds(merged, "de"))
    }

    // endregion

    // region Russian

    @Test
    fun `russian said as written scores 100`() {
        assertEquals(100, score("Мать дома", "mˈɑtʲ dˈoma", "mɑtʲ doma", "ru").score)
        assertEquals(100, score("молоко", "mʌɭʌkˈo", "mʌɭʌko", "ru-RU").score)
    }

    @Test
    fun `a soft consonant said hard scores lower and is explained`() {
        // мать (mother) said as мат.
        val result = score("мать", "mˈɑtʲ", "mɑt", "ru")
        assertTrue(result.score < 100, "score was ${result.score}")
        val softness = result.alignment.last()
        assertEquals("ʲ", softness.expected)
        assertEquals(MatchStatus.MISSED, softness.status)
        assertEquals(listOf<String?>("ru.soft.hard"), ruleIds(result, "ru"))
    }

    @Test
    fun `yery said as i scores lower and is explained`() {
        val result = score("ты", "tˈy", "ti", "ru")
        assertTrue(result.score < 100)
        assertEquals(MatchStatus.CLOSE, result.alignment[1].status)
        assertEquals(listOf<String?>("ru.y.i"), ruleIds(result, "ru"))
        // The textbook symbol for the same vowel is not an error.
        assertEquals(100, score("ты", "tˈy", "tɨ", "ru").score)
    }

    @Test
    fun `an unstressed o read at full value scores lower and is explained`() {
        val result = score("молоко", "mʌɭʌkˈo", "moɭoko", "ru")
        assertTrue(result.score < 100)
        assertEquals(listOf<String?>("ru.o.unreduced"), ruleIds(result, "ru"))
        // A plain or a central vowel for the reduced one is the same phoneme.
        assertEquals(100, score("молоко", "mʌɭʌkˈo", "maɭako", "ru").score)
        assertEquals(100, score("молоко", "mʌɭʌkˈo", "mɐɭɐko", "ru").score)
    }

    @Test
    fun `sh said as s and z said as s are explained`() {
        val sh = score("наш", "nˈɑʃ", "nɑs", "ru")
        assertTrue(sh.score < 100)
        assertEquals(listOf<String?>("ru.sh.s"), ruleIds(sh, "ru"))

        val z = score("коза", "kʌzˈɑ", "kʌsɑ", "ru")
        assertTrue(z.score < 100)
        assertEquals(listOf<String?>("ru.z.s"), ruleIds(z, "ru"))
    }

    @Test
    fun `the model's raw russian tokens match a folded reference`() {
        // ночь: the published reference is folded (tʃ), the model emits tʃʲ.
        assertEquals(100, score("ночь", "nˈotʃ", "notʃʲ", "ru").score)
        // A plain Spanish CH for Ч is the same phoneme, not a missing softness.
        val plain = score("ночь", "nˈotʃʲ", "notʃ", "ru")
        assertEquals(100, plain.score)
        assertTrue(ruleIds(plain, "ru").isEmpty())
        // дверь: espeak's final РЬ (rɪ^) is a soft R.
        assertEquals(100, score("дверь", "dvʲˈerɪ^", "dvʲerʲ", "ru").score)
        // любить: the fronted У (u") is an allophone of У.
        assertEquals(100, score("любить", "ɭʲu\"bʲˈitʲ", "ɭʲubʲitʲ", "ru").score)
        assertEquals(100, score("любить", "ɭʲubʲˈitʲ", "ɭʲu\"bʲitʲ", "ru").score)
    }

    @Test
    fun `the russian folds stay out of every other language`() {
        // In English the extra characters are still segments, exactly as before.
        val english = score("x", "tʃ", "tʃʲ", "en")
        assertEquals(listOf("tʃ", "ʲ"), PhoneticComparator.phonesOf(english.normalizedActual))
        assertTrue(english.score < 100)
        val french = score("x", "u", "u\"", "fr")
        assertTrue(french.score < 100)
    }

    @Test
    fun `cyrillic letters carry the status of the phone they spell`() {
        val result = score("мать", "mˈɑtʲ", "mɑt", "ru")
        val letters = PhoneticComparator.generateLetterFeedback("мать", "mˈɑtʲ", result.alignment, "ru")
        assertEquals(listOf("м", "а", "т", "ь"), letters.map { it.char })
        assertEquals(
            listOf(MatchStatus.PERFECT, MatchStatus.PERFECT, MatchStatus.PERFECT, MatchStatus.MISSED),
            letters.map { it.status },
        )
    }

    // endregion

    // region Matrices

    @Test
    fun `german and russian matrices are well formed`() {
        for (language in listOf("de", "ru")) {
            val matrix = PhoneticComparator.similarityMatrix(language)
            assertTrue(matrix.isNotEmpty(), "$language has no matrix")
            for ((pair, value) in matrix) {
                assertEquals(2, pair.size, "$language: $pair must be two distinct phones")
                assertTrue(value > 0.0 && value < 1.0, "$language: $pair -> $value out of range")
            }
        }
    }

    @Test
    fun `similarity is symmetric`() {
        val pairs = mapOf(
            "de" to listOf("ʏ" to "ʊ", "ç" to "ʃ", "ɑː" to "a", "ɾ" to "ʁ", "ɜ" to "ə"),
            "ru" to listOf("y" to "i", "ʌ" to "o", "ʃ" to "ɕ", "ʑ" to "z", "ɭ" to "l"),
        )
        for ((language, list) in pairs) {
            for ((a, b) in list) {
                val forward = score("x", a, b, language)
                val backward = score("x", b, a, language)
                assertEquals(forward.score, backward.score, "$language: $a/$b")
                assertEquals(forward.alignment.single().status, backward.alignment.single().status)
            }
        }
    }

    @Test
    fun `russian has its own matrix instead of the english fallback`() {
        // ʃ/ʒ is CLOSE in Russian (жар/шар) and has no English entry.
        assertEquals(MatchStatus.CLOSE, score("жар", "ʒˈɑr", "ʃɑr", "ru").alignment[0].status)
        assertEquals(MatchStatus.MISSED, score("x", "ʒ", "ʃ", "en").alignment[0].status)
    }

    @Test
    fun `a real contrast never reaches the perfect threshold`() {
        val contrasts = mapOf(
            "de" to listOf("ʏ" to "ʊ", "yː" to "uː", "øː" to "eː", "ç" to "ʃ", "ç" to "x", "ɑː" to "a", "z" to "s"),
            "ru" to listOf("y" to "i", "ʌ" to "o", "ʃ" to "s", "ʃ" to "ɕ", "ʑ" to "z", "z" to "s", "v" to "b"),
        )
        for ((language, list) in contrasts) {
            for ((a, b) in list) {
                val status = score("x", a, b, language).alignment.single().status
                assertTrue(status != MatchStatus.PERFECT, "$language: $a said as $b must not be a clean match")
            }
        }
    }

    // endregion

    // region Hints

    @Test
    fun `russian hints follow the target language and the ui language`() {
        val en = PhoneticComparator.getMinimalPairDescription("y", "i", "en", "ru")
        val es = PhoneticComparator.getMinimalPairDescription("y", "i", "es", "ru-RU")
        assertTrue(en!!.contains("быть"), "Russian hint should use Russian examples: $en")
        assertTrue(es!!.contains("быть") && es != en, "Spanish UI should get its own text: $es")
        // The French tu/tout hint shares the symbols but must not reach Russian.
        assertEquals(null, PhoneticComparator.getMinimalPairDescription("y", "u", "en", "ru"))
    }

    @Test
    fun `the long a hint also covers the espeak spelling`() {
        val espeak = PhoneticComparator.getMinimalPairDescription("ɑː", "a", "en", "de")
        assertEquals(PhoneticComparator.getMinimalPairDescription("aː", "a", "en", "de"), espeak)
        assertTrue(espeak!!.contains("Stadt"))
    }

    // endregion
}
