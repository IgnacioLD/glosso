package com.glosso.engine.phonetics

import com.glosso.engine.model.MasteryLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A reading with a weak function word elided must keep every per-word score on
 * its own sentence word. The elided reading used to drop the word's slot, so the
 * scores after it moved one word to the left and the last word lost its score.
 */
class ElisionWordIndexTest {

    private val text = "See you at the gate later"
    private val ipa = "sˈi jˈu ˈæt ðə ɡˈeɪt lˈeɪtɚ"

    @Test
    fun `an elided weak word keeps the word scores in place`() {
        // "the" dropped, everything else said cleanly.
        val result = PhoneticComparator.calculateScoringResult(text, ipa, "sijuætɡeɪtleɪtɚ")
        assertEquals(6, result.words.size)
        assertEquals((0..5).toList(), result.words.map { it.index })
        assertEquals(4, result.words[4].phoneCount) // gate: ɡ e ɪ t
        assertEquals(0, result.words[3].phoneCount) // the: elided
        assertEquals(100, result.words[3].score)
        assertEquals(MasteryLevel.MASTERED, result.words[3].level)

        val feedback = FeedbackAssembler.assemble(text, ipa, result)
        assertEquals(listOf("See", "you", "at", "the", "gate", "later"), feedback.words.map { it.text })
        assertEquals(result.words[4].score, feedback.words.single { it.text == "gate" }.score)
    }

    @Test
    fun `a mistake after the elided word is reported on the word that has it`() {
        // "the" dropped and "gate" said as "get": the miss belongs to "gate".
        val result = PhoneticComparator.calculateScoringResult(text, ipa, "sijuætɡɛtleɪtɚ")
        val feedback = FeedbackAssembler.assemble(text, ipa, result)
        val weakest = feedback.words.minBy { it.score }
        assertEquals("gate", weakest.text)
        assertEquals(100, feedback.words.single { it.text == "later" }.score)
    }

    @Test
    fun `the first word can be the elided one`() {
        val result = PhoneticComparator.calculateScoringResult("I think so", "ˈaɪ θˈɪŋk sˈoʊ", "θɪŋksoʊ")
        assertEquals(3, result.words.size)
        assertEquals(0, result.words[0].phoneCount)
        assertEquals(100, result.score)
    }
}
