package com.glosso.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoringConfigTest {

    @Test
    fun `mastery band starts at the mastery threshold`() {
        assertTrue(ScoringConfig.isMasteryBand(ScoringConfig.MASTERY_THRESHOLD))
        assertTrue(ScoringConfig.isMasteryBand(100))
        assertFalse(ScoringConfig.isMasteryBand(ScoringConfig.MASTERY_THRESHOLD - 1))
    }

    @Test
    fun `close band starts at the close threshold`() {
        assertTrue(ScoringConfig.isCloseBand(ScoringConfig.CLOSE_THRESHOLD))
        assertFalse(ScoringConfig.isCloseBand(ScoringConfig.CLOSE_THRESHOLD - 1))
        assertFalse(ScoringConfig.isCloseBand(0))
    }

    @Test
    fun `bands are ordered so mastery is always also close`() {
        assertTrue(ScoringConfig.CLOSE_THRESHOLD < ScoringConfig.MASTERY_THRESHOLD)
        for (score in 0..100) {
            if (ScoringConfig.isMasteryBand(score)) {
                assertTrue(ScoringConfig.isCloseBand(score), "mastery at $score should also be close")
            }
        }
    }

    @Test
    fun `a strong attempt with every word intact qualifies`() {
        assertTrue(
            ScoringConfig.qualifiesForMastery(
                score = 100,
                completeness = 100,
                wordScores = listOf(100, 95, 88)
            )
        )
    }

    @Test
    fun `low completeness does not qualify`() {
        assertFalse(
            ScoringConfig.qualifiesForMastery(
                score = 95,
                completeness = ScoringConfig.COMPLETENESS_FLOOR - 1,
                wordScores = listOf(100, 100)
            )
        )
    }

    @Test
    fun `one destroyed word does not qualify however good the average`() {
        assertFalse(
            ScoringConfig.qualifiesForMastery(
                score = 100,
                completeness = 100,
                wordScores = listOf(100, 100, ScoringConfig.WORD_FLOOR - 1)
            )
        )
    }

    @Test
    fun `weakest word index points at the lowest score`() {
        assertEquals(2, ScoringConfig.weakestWordIndex(listOf(90, 80, 10)))
        assertEquals(null, ScoringConfig.weakestWordIndex(emptyList()))
    }

    @Test
    fun `levelFor maps score bands and perfection`() {
        assertEquals(MasteryLevel.PERFECT, ScoringConfig.levelFor(100, perfect = true))
        // Flawless wins even when the score is merely mastery-high.
        assertEquals(MasteryLevel.PERFECT, ScoringConfig.levelFor(92, perfect = true))

        assertEquals(MasteryLevel.MASTERED, ScoringConfig.levelFor(ScoringConfig.MASTERY_THRESHOLD))
        assertEquals(MasteryLevel.ALMOST, ScoringConfig.levelFor(ScoringConfig.MASTERY_THRESHOLD - 1))
        assertEquals(MasteryLevel.ALMOST, ScoringConfig.levelFor(ScoringConfig.CLOSE_THRESHOLD))
        assertEquals(MasteryLevel.ROUGH, ScoringConfig.levelFor(ScoringConfig.CLOSE_THRESHOLD - 1))
        assertEquals(MasteryLevel.ROUGH, ScoringConfig.levelFor(ScoringConfig.WORD_FLOOR))
        assertEquals(MasteryLevel.NOT_YET, ScoringConfig.levelFor(ScoringConfig.WORD_FLOOR - 1))
        assertEquals(MasteryLevel.NOT_YET, ScoringConfig.levelFor(0))
    }

    @Test
    fun `a hard error holds a high-scoring word below mastered`() {
        // One substituted vowel in a three-phone word averages to 90; that is not
        // a clean production, so it must read ALMOST rather than MASTERED.
        assertEquals(MasteryLevel.MASTERED, ScoringConfig.levelFor(90))
        assertEquals(MasteryLevel.ALMOST, ScoringConfig.levelFor(90, hardError = true))
        // A hard error with a low score drops further down the ladder instead.
        assertEquals(MasteryLevel.ROUGH, ScoringConfig.levelFor(60, hardError = true))
        // An allophone the scorer tolerates is not a hard error.
        assertEquals(MasteryLevel.MASTERED, ScoringConfig.levelFor(92, hardError = false))
    }

    @Test
    fun `a dropped sound blocks mastery on a short take however good the average`() {
        assertTrue(
            ScoringConfig.qualifiesForMastery(score = 97, completeness = 100, wordScores = listOf(97), referencePhones = 4)
        )
        // A single word or short drill tolerates no slip: its one wrong sound is the point.
        for (phones in listOf(1, 4, ScoringConfig.PHONES_PER_TOLERATED_SLIP - 1)) {
            assertFalse(
                ScoringConfig.qualifiesForMastery(
                    score = 97,
                    completeness = 100,
                    wordScores = listOf(97),
                    droppedOrAdded = 1,
                    referencePhones = phones,
                ),
                "one slip in $phones sounds must not be mastered",
            )
        }
    }

    @Test
    fun `a long take tolerates one dropped or added sound per fifteen and no more`() {
        assertEquals(0, ScoringConfig.toleratedSlips(0))
        assertEquals(0, ScoringConfig.toleratedSlips(14))
        assertEquals(1, ScoringConfig.toleratedSlips(15))
        assertEquals(1, ScoringConfig.toleratedSlips(29))
        assertEquals(3, ScoringConfig.toleratedSlips(46))

        fun good(slips: Int, phones: Int, score: Int = 95) = ScoringConfig.qualifiesForMastery(
            score = score,
            completeness = 100,
            wordScores = listOf(100, 90, 100),
            droppedOrAdded = slips,
            referencePhones = phones,
        )
        assertTrue(good(slips = 1, phones = 15))
        assertFalse(good(slips = 2, phones = 29))
        assertTrue(good(slips = 3, phones = 46))
        assertFalse(good(slips = 4, phones = 46))
        // The tolerance never lowers the other gates.
        assertFalse(good(slips = 1, phones = 30, score = ScoringConfig.MASTERY_THRESHOLD - 1))
        assertFalse(
            ScoringConfig.qualifiesForMastery(
                score = 95, completeness = 100, wordScores = listOf(100, ScoringConfig.WORD_FLOOR - 1),
                droppedOrAdded = 1, referencePhones = 30,
            )
        )
        assertFalse(
            ScoringConfig.qualifiesForMastery(
                score = 95, completeness = ScoringConfig.COMPLETENESS_FLOOR - 1, wordScores = listOf(100),
                referencePhones = 30,
            )
        )
    }

    @Test
    fun `a substituted sound is never tolerated - however long the take`() {
        // Swapping one phoneme for another is what minimal-pair drills test
        // ("cup" said as "cap"), so length never buys it back.
        for (phones in listOf(4, 15, 46, 200)) {
            assertFalse(
                ScoringConfig.qualifiesForMastery(
                    score = 98,
                    completeness = 100,
                    wordScores = listOf(100, 90, 100),
                    substitutions = 1,
                    referencePhones = phones,
                ),
                "a substitution in $phones sounds must not be mastered",
            )
        }
    }

    @Test
    fun `verdict bands map score and gate to the documented level`() {
        // range -> label, as documented on ScoringConfig.
        assertEquals(MasteryLevel.PERFECT, ScoringConfig.sentenceLevel(100, isMastery = true, perfect = true))
        assertEquals(MasteryLevel.MASTERED, ScoringConfig.sentenceLevel(100, isMastery = true))
        assertEquals(MasteryLevel.MASTERED, ScoringConfig.sentenceLevel(85, isMastery = true))
        for (score in 70..99) assertEquals(MasteryLevel.ALMOST, ScoringConfig.sentenceLevel(score, isMastery = false))
        for (score in 50..69) assertEquals(MasteryLevel.ROUGH, ScoringConfig.sentenceLevel(score, isMastery = false))
        for (score in 0..49) assertEquals(MasteryLevel.NOT_YET, ScoringConfig.sentenceLevel(score, isMastery = false))
    }

    @Test
    fun `sentenceLevel respects the mastery gate`() {
        // A band-high score that failed the gate (skipped words or a destroyed
        // word) must not be reported as MASTERED.
        assertEquals(
            MasteryLevel.ALMOST,
            ScoringConfig.sentenceLevel(92, isMastery = false)
        )
        assertEquals(
            MasteryLevel.MASTERED,
            ScoringConfig.sentenceLevel(92, isMastery = true)
        )
        assertEquals(
            MasteryLevel.PERFECT,
            ScoringConfig.sentenceLevel(100, isMastery = true, perfect = true)
        )
        assertEquals(
            MasteryLevel.NOT_YET,
            ScoringConfig.sentenceLevel(10, isMastery = false)
        )
    }

    @Test
    fun `levels are ordered best to worst`() {
        assertTrue(MasteryLevel.PERFECT < MasteryLevel.MASTERED)
        assertTrue(MasteryLevel.MASTERED < MasteryLevel.ALMOST)
        assertTrue(MasteryLevel.ALMOST < MasteryLevel.ROUGH)
        assertTrue(MasteryLevel.ROUGH < MasteryLevel.NOT_YET)
    }
}
