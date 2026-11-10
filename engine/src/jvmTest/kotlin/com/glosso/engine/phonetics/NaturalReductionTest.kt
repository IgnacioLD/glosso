package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the inversion the engine audit documented: the note is a
 * normalized edit distance against a citation-form dictionary, so a native who
 * reduces in connected speech scored *below* a learner who over-articulates
 * (docs/ENGINE-STATUS-2026-09-24.md, section 1).
 *
 * The natural hypotheses here are real recognizer output from the committed
 * arbiter probe (`engine/src/jvmTest/resources/probe_tts_ref.json`); the over-pronounced
 * counterparts are the same reference fully articulated. Before, the native clip
 * lost the weak word "you"; the fix makes weak-word elision a reduction again,
 * and the controls prove the forgiveness is not universal.
 */
class NaturalReductionTest {

    private fun score(expected: String, actual: String): Int =
        PhoneticComparator.calculateScoringResult(
            text = "case",
            expected = expected,
            actual = actual,
        ).score

    @Test
    fun `a native who elides a weak word is not punished`() {
        val reference = "sˈi jˈu lˈeɪtɚ"
        // Real native output: "See you later" with the weak "you" elided
        // (probe common_voice_en_10410522).
        val natural = score(reference, "siːleɪɾɚ")
        // An over-articulated take says every citation-form phone, "you" included.
        val overPronounced = score(reference, "sijuleɪtɚ")
        println("NATURAL_REDUCTION natural=$natural overPronounced=$overPronounced")

        assertTrue(natural >= 90, "eliding the weak word 'you' scored $natural")
        assertEquals(100, overPronounced)
    }

    @Test
    fun `a real non-native over-pronunciation still scores high`() {
        // Probe common_voice_en_19984007: every citation-form segment produced.
        val overPronounced = score("sˈi tˈaɪm zˈoʊn", "siːtaɪmzoʊn")
        println("NATURAL_REDUCTION overPronouncedReal=$overPronounced")
        assertTrue(overPronounced >= 95, "over-pronunciation scored $overPronounced")
    }

    @Test
    fun `dropping a content word is still a hard error`() {
        val reference = "sˈi jˈu lˈeɪtɚ"
        // "later" is a content word: dropping it must not be excused.
        val droppedContent = score(reference, "siju")
        println("NATURAL_REDUCTION droppedContent=$droppedContent")
        assertTrue(droppedContent <= 75, "dropping 'later' scored $droppedContent")
    }

    @Test
    fun `a vowel-quality substitution is still a substitution`() {
        // bat/bet is a real vowel contrast, not a reduction: it must stay an error.
        val batForBet = score("bˈæt", "bˈɛt")
        println("NATURAL_REDUCTION batForBet=$batForBet")
        assertTrue(batForBet < 80, "saying bet for bat scored $batForBet")
    }

    @Test
    fun `saying only the content word does not pass`() {
        // Review of #104: an elided reading must drop one weak word, not all of
        // them. Saying just the content word is not natural reduction.
        val onlyCat = PhoneticComparator.calculateScoringResult(
            text = "I have a cat",
            expected = "aɪ hæv ə kæt",
            actual = "kæt",
        ).score
        val onlyWhat = PhoneticComparator.calculateScoringResult(
            text = "it is what it is",
            expected = "ɪt ɪz wʌt ɪt ɪz",
            actual = "wʌt",
        ).score
        println("NATURAL_REDUCTION onlyCat=$onlyCat onlyWhat=$onlyWhat")
        assertTrue(onlyCat < 60, "saying only 'cat' for 'I have a cat' scored $onlyCat")
        assertTrue(onlyWhat < 60, "saying only 'what' for 'it is what it is' scored $onlyWhat")
    }
}
