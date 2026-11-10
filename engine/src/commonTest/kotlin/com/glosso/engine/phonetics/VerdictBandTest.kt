package com.glosso.engine.phonetics

import com.glosso.engine.model.MasteryLevel
import com.glosso.engine.model.ScoringConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The verdict bands of [ScoringConfig] as the learner meets them, through the real scorer. */
class VerdictBandTest {

    private val text = "the train arrives at the right stop"
    private val reference = "ðə tɹˈeɪn ɚˈaɪvz ˈæt ðə ɹˈaɪt stˈɑp"

    private fun score(actual: String) = PhoneticComparator.calculateScoringResult(text, reference, actual)

    @Test
    fun `a clean long take is perfect`() {
        assertEquals(MasteryLevel.PERFECT, score("ðətɹeɪnɚaɪvzætðəɹaɪtstɑp").level)
    }

    @Test
    fun `one dropped sound in a long take is still a good take - and its word is flagged`() {
        assertTrue(ScoringConfig.toleratedSlips(PhoneticComparator.phonesOf(reference).size) >= 1)
        val result = score("ðətɹeɪnɚaɪzætðəɹaɪtstɑp") // "arrives" without its v
        assertTrue(result.score in ScoringConfig.MASTERY_THRESHOLD until 100, "score ${result.score}")
        assertEquals(MasteryLevel.MASTERED, result.level)
        assertTrue(result.isMastery)
        assertEquals(MasteryLevel.ALMOST, result.words[2].level)
    }

    @Test
    fun `one substituted sound in a long take is not a good take`() {
        val result = score("ðətɹeɪnɚaɪvzætðəlaɪtstɑp") // "right" said as "light"
        assertTrue(result.score >= ScoringConfig.MASTERY_THRESHOLD, "score ${result.score}")
        assertEquals(MasteryLevel.ALMOST, result.level)
    }

    @Test
    fun `a dropped sound in a single word is not a good take`() {
        val result = PhoneticComparator.calculateScoringResult("station", "stˈeɪʃən", "steɪʃə")
        assertEquals(0, ScoringConfig.toleratedSlips(PhoneticComparator.phonesOf("stˈeɪʃən").size))
        assertTrue(result.level > MasteryLevel.MASTERED, "level ${result.level} at score ${result.score}")
    }
}
