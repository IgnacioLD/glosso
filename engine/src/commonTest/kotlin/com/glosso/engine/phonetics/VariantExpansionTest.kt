package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How reference variants are expanded, and the language codes the scorer keys on.
 * The expansion must score every distinct reading exactly once and pick the same
 * best reading however many spellings of it the variant list carries.
 */
class VariantExpansionTest {

    private fun score(
        expected: String,
        actual: String,
        variants: List<List<String>> = emptyList(),
        distinctCap: Boolean = PhoneticComparator.DISTINCT_VARIANT_CAP_DEFAULT,
        language: String = "en",
    ) = PhoneticComparator.calculateScoringResult(
        text = "probe",
        expected = expected,
        actual = actual,
        language = language,
        expectedWordVariants = variants,
        dialectWidening = true,
        distinctVariantCap = distinctCap,
    )

    @Test
    fun `stress-only duplicate readings score exactly like the deduplicated list`() {
        val expected = "ðə bˈɪn ˈɪz ɪn ðə ɡˈeɪt"
        val actual = "ðʌbɪnɪzɪnðiɡeɪt"
        val withDuplicates = listOf(
            listOf("ðə", "ðˈʌ", "ði"), emptyList(), listOf("ˈɪz", "ɪz"),
            listOf("ɪn", "ˈɪn"), listOf("ðə", "ðˈʌ", "ði"), emptyList(),
        )
        val deduplicated = listOf(
            listOf("ðə", "ðˈʌ", "ði"), emptyList(), emptyList(),
            emptyList(), listOf("ðə", "ðˈʌ", "ði"), emptyList(),
        )
        assertEquals(score(expected, actual, deduplicated), score(expected, actual, withDuplicates))
    }

    /**
     * Twelve words whose only "variant" is the same reading with a stress mark,
     * plus "thirty" with its real British reading: 2^12 * 2 = 8192 raw
     * combinations, but only 2 distinct references.
     */
    private val longExpected = List(12) { "ˈɪz" }.joinToString(" ") + " θˈɝdi"
    private val longVariants = List(12) { listOf("ˈɪz", "ɪz") } + listOf(listOf("θˈɝdi", "θˈɝti"))
    private val britishTake = "ɪz".repeat(12) + "θɜːti"

    @Test
    fun `by default the raw count still trips the cap and the variants are dropped`() {
        val capped = score(longExpected, britishTake, longVariants)
        val single = score(longExpected, britishTake)
        assertEquals(single, capped)
    }

    @Test
    fun `counting distinct readings keeps the variant a stress duplicate used to hide`() {
        val flagged = score(longExpected, britishTake, longVariants, distinctCap = true)
        val capped = score(longExpected, britishTake, longVariants)
        assertTrue(flagged.score > capped.score, "flagged=${flagged.score} capped=${capped.score}")
        assertEquals(100, flagged.score)
    }

    @Test
    fun `the distinct cap never changes a sentence under the raw cap`() {
        val expected = "ðə bˈɪn ˈɪz ɪn ðə ɡˈeɪt"
        val variants = listOf(
            listOf("ðə", "ðˈʌ", "ði"), emptyList(), listOf("ˈɪz", "ɪz"),
            listOf("ɪn", "ˈɪn"), listOf("ðə", "ðˈʌ", "ði"), emptyList(),
        )
        for (actual in listOf("ðʌbɪnɪzɪnðiɡeɪt", "dəbinisinðəɡet", "bɪnɡeɪt")) {
            assertEquals(score(expected, actual, variants), score(expected, actual, variants, distinctCap = true))
        }
    }

    @Test
    fun `a region-tagged language scores with its base language matrix`() {
        // /p/ vs /b/ is partial credit in French and a plain miss in English.
        val french = score("pa", "ba", language = "fr")
        assertEquals(french, score("pa", "ba", language = "fr-FR"))
        assertEquals(french, score("pa", "ba", language = "FR"))
        assertTrue(french.score > score("pa", "ba", language = "en").score)
    }

    @Test
    fun `a region-tagged target language still gets its own minimal-pair hint`() {
        val hint = PhoneticComparator.getMinimalPairDescription("y", "u", uiLanguage = "en", targetLanguage = "fr-FR")
        assertNotNull(hint)
        assertEquals(PhoneticComparator.getMinimalPairDescription("y", "u", "en", "fr"), hint)
    }
}
