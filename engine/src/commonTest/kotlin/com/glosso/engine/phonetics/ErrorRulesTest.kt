package com.glosso.engine.phonetics

import com.glosso.engine.model.PhonemeErrorKindModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorRulesTest {

    private fun error(
        expected: String,
        actual: String,
        status: MatchStatus = MatchStatus.MISSED,
    ) = ErrorRules.analyze(listOf(PhonemeMatch(expected, actual, status)))

    @Test
    fun `a known contrast names the error and offers a drill`() {
        val errors = error("θ", "s")
        assertEquals(1, errors.size)
        assertEquals("en.th.stopping", errors[0].ruleId)
        assertEquals(PhonemeErrorKindModel.SUBSTITUTION, errors[0].kind)
        assertEquals(listOf("think", "sink"), errors[0].examples)
    }

    @Test
    fun `a perfect sound is never an error`() {
        assertTrue(error("θ", "θ", MatchStatus.PERFECT).isEmpty())
    }

    @Test
    fun `an unknown substitution is not explained away`() {
        assertTrue(error("q", "z").isEmpty())
    }

    @Test
    fun `a dropped sound is a deletion`() {
        val errors = error("ɹ", "-")
        assertEquals(1, errors.size)
        assertEquals(PhonemeErrorKindModel.DELETION, errors[0].kind)
        assertEquals("ɹ", errors[0].phoneme)
    }

    @Test
    fun `a forgiven word-final release is not reported as a deletion`() {
        val errors = error("t", "-", MatchStatus.CLOSE)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `the same error repeated in a sentence is reported once`() {
        val errors = ErrorRules.analyze(
            listOf(
                PhonemeMatch("θ", "s", MatchStatus.MISSED),
                PhonemeMatch("θ", "s", MatchStatus.MISSED),
            ),
        )
        assertEquals(1, errors.size)
    }

    @Test
    fun `an error carries the word it was heard in`() {
        val errors = ErrorRules.analyze(
            listOf(
                PhonemeMatch("k", "k", MatchStatus.PERFECT, wordIndex = 0),
                PhonemeMatch("θ", "s", MatchStatus.MISSED, wordIndex = 1),
            ),
        )
        assertEquals(1, errors.size)
        assertEquals(1, errors.single().wordIndex)
    }

    @Test
    fun `english rules are unaffected by other languages`() {
        assertTrue(error("θ", "s").isNotEmpty())
    }

    @Test
    fun `french contrasts are explained with french rules`() {
        val errors = ErrorRules.analyze(
            listOf(PhonemeMatch("\u0251\u0303", "\u0254\u0303", MatchStatus.MISSED)),
            language = "fr",
        )
        assertEquals(1, errors.size)
        assertEquals("fr.an.on", errors[0].ruleId)
        assertEquals(listOf("dans", "don"), errors[0].examples)
    }

    @Test
    fun `a region code selects the language rules`() {
        val errors = ErrorRules.analyze(
            listOf(PhonemeMatch("y", "u", MatchStatus.MISSED)),
            language = "fr-FR",
        )
        assertEquals("fr.u.ou", errors.single().ruleId)
    }

    @Test
    fun `german contrasts are explained with german rules`() {
        val errors = ErrorRules.analyze(
            listOf(PhonemeMatch("ç", "ʃ", MatchStatus.CLOSE)),
            language = "de",
        )
        assertEquals("de.ich.sch", errors.single().ruleId)
        assertEquals(listOf("Kirche", "Kirsche"), errors.single().examples)
        assertEquals("de.ue.short.u", ErrorRules.ruleFor("ʏ", "ʊ", "de-DE")?.id)
        assertEquals("de.h.dropping", ErrorRules.ruleFor("h", null, "de")?.id)
    }

    @Test
    fun `russian contrasts are explained with russian rules`() {
        val errors = ErrorRules.analyze(
            listOf(
                PhonemeMatch("y", "i", MatchStatus.CLOSE),
                PhonemeMatch("ʲ", "-", MatchStatus.MISSED),
            ),
            language = "ru",
        )
        assertEquals(listOf<String?>("ru.y.i", "ru.soft.hard"), errors.map { it.ruleId })
        assertEquals(listOf("мать", "мат"), errors[1].examples)
        assertEquals("ru.o.unreduced", ErrorRules.ruleFor("ʌ", "o", "ru-RU")?.id)
    }

    @Test
    fun `the same pair is taught per language`() {
        // /y/ is Ü in French and German and Ы in Russian; /z/ said as /s/ exists in all three.
        assertEquals("fr.u.ou", ErrorRules.ruleFor("y", "u", "fr")?.id)
        assertEquals("ru.y.u", ErrorRules.ruleFor("y", "u", "ru")?.id)
        assertEquals("de.ue.u", ErrorRules.ruleFor("y", "u", "de")?.id)
        assertEquals(
            listOf<String?>("fr.z.s", "de.z.s", "ru.z.s"),
            listOf("fr", "de", "ru").map { ErrorRules.ruleFor("z", "s", it)?.id },
        )
    }

    @Test
    fun `rule ids are unique and carry their language prefix`() {
        for (language in listOf("de", "ru")) {
            val ids = ErrorRules.ruleIds(language)
            assertEquals(ids.size, ids.toSet().size, "$language: duplicate rule id")
            assertTrue(ids.all { it.startsWith("$language.") }, "$language: $ids")
        }
    }

    @Test
    fun `an unknown language is never given a made-up rule`() {
        assertTrue(
            ErrorRules.analyze(
                listOf(PhonemeMatch("θ", "s", MatchStatus.MISSED)),
                language = "klingon",
            ).isEmpty(),
        )
    }
}
