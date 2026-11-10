package com.glosso.engine.accent

import com.glosso.engine.phonetics.PhoneticComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccentScoringTest {

    private val canonical = "ðˈæt ˈɪz tˈu pˈaʊndz"
    private val reference = mapOf("en-GB" to "ðˈat ɪz tˈuː pˈaʊndz")
    private val heard = "ðatɪztuːpaʊndz"

    private fun single(actual: String) = PhoneticComparator.calculateScoringResult(
        text = "That is two pounds.",
        expected = canonical,
        actual = actual,
        language = "en",
    ).score

    @Test
    fun `off it is the single-reference score and reports no accent`() {
        val outcome = AccentScoring.score("That is two pounds.", canonical, heard, "en", accentReferences = reference)

        assertEquals(single(heard), outcome.result.score)
        assertNull(outcome.accent)
    }

    @Test
    fun `on the published accent reference lifts a native take to 100`() {
        val outcome = AccentScoring.score(
            "That is two pounds.", canonical, heard, "en", multiAccent = true, accentReferences = reference,
        )

        assertTrue(single(heard) < 100)
        assertEquals(100, outcome.result.score)
        assertEquals("en-GB", outcome.accent?.system?.accent)
    }

    @Test
    fun `the feedback keeps the dictionary's phones and reports the matched words as right`() {
        val off = AccentScoring.score("That is two pounds.", canonical, heard, "en")
        val on = AccentScoring.score(
            "That is two pounds.", canonical, heard, "en", multiAccent = true, accentReferences = reference,
        )

        // Same reference phones as before the flag: the profile and the drill
        // targets are keyed by them, so the notation must not change.
        assertEquals(off.result.alignment.map { it.expected }, on.result.alignment.map { it.expected })
        assertEquals(off.result.normalizedExpected, on.result.normalizedExpected)
        assertTrue(off.result.alignment.any { it.status != com.glosso.engine.phonetics.MatchStatus.PERFECT })
        assertTrue(on.result.alignment.all { it.status == com.glosso.engine.phonetics.MatchStatus.PERFECT })
        assertTrue(on.result.words.all { it.score == 100 })
    }

    @Test
    fun `on it never scores below the single-reference score`() {
        val takes = listOf(heard, "ðætɪztupaʊndz", "datistupaʊns", "ðatɪztuːpaʊnz", "zatɪstuːbaʊndz", "tuːpaʊndz")
        for (take in takes) {
            for (references in listOf(emptyMap(), reference)) {
                val outcome = AccentScoring.score(
                    "That is two pounds.", canonical, take, "en", multiAccent = true, accentReferences = references,
                )
                assertTrue(outcome.result.score >= single(take), "$take: ${outcome.result.score} < ${single(take)}")
            }
        }
    }

    @Test
    fun `a real error stays an error with the reference published`() {
        val outcome = AccentScoring.score(
            "That is two pounds.", canonical, "datɪztuːbaʊndz", "en", multiAccent = true, accentReferences = reference,
        )

        assertTrue(outcome.result.score < 98, "th-stopping and b for p must cost: ${outcome.result.score}")
    }

    @Test
    fun `a language without accent systems is scored as before`() {
        val outcome = AccentScoring.score("bonjour", "bɔ̃ʒuʁ", "bɔ̃ʒuʁ", "fr", multiAccent = true)

        assertEquals(100, outcome.result.score)
        assertNull(outcome.accent)
    }

    @Test
    fun `on it never reads a worse level or loses mastery against the single reference`() {
        val takes = listOf(heard, "ðætɪztupaʊndz", "ðætɪztuːpaʊndz", "datistupaʊns", "ðatɪztuːpaʊnz", "zatɪstuːbaʊndz", "tuːpaʊndz")
        for (take in takes) {
            val alone = PhoneticComparator.calculateScoringResult("That is two pounds.", canonical, take, "en")
            val outcome = AccentScoring.score(
                "That is two pounds.", canonical, take, "en", multiAccent = true, accentReferences = reference,
            )
            assertTrue(outcome.result.level <= alone.level, "$take: ${outcome.result.level} is worse than ${alone.level}")
            assertTrue(outcome.result.isMastery || !alone.isMastery, "$take: mastery lost")
        }
    }
}
