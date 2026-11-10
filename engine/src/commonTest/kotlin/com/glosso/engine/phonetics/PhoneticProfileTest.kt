package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneticProfileTest {

    private fun attempt(vararg matches: PhonemeMatch) = matches.toList()

    @Test
    fun `a fresh attempt builds stats from scratch`() {
        val profile = PhoneticProfile.fold(
            null,
            attempt(
                PhonemeMatch("θ", "θ", MatchStatus.PERFECT),
                PhonemeMatch("æ", "ɛ", MatchStatus.CLOSE),
            ),
        )

        val theta = profile.stats.first { it.phoneme == "θ" }
        val ae = profile.stats.first { it.phoneme == "æ" }
        assertEquals(1, theta.attempts)
        assertEquals(1, theta.correct)
        assertEquals(100, theta.accuracy)
        assertEquals(0, ae.correct)
        assertEquals(0, ae.accuracy)
        assertEquals("ɛ", ae.typicalError)
    }

    @Test
    fun `attempts accumulate across calls`() {
        val first = PhoneticProfile.fold(null, attempt(PhonemeMatch("θ", "s", MatchStatus.MISSED)))
        val second = PhoneticProfile.fold(
            first,
            attempt(
                PhonemeMatch("θ", "θ", MatchStatus.PERFECT),
                PhonemeMatch("θ", "θ", MatchStatus.PERFECT),
            ),
        )

        val theta = second.stats.first { it.phoneme == "θ" }
        assertEquals(3, theta.attempts)
        assertEquals(2, theta.correct)
        assertEquals(66, theta.accuracy)
        assertEquals(1, theta.errors["s"])
    }

    @Test
    fun `a dropped sound is recorded distinctly from produced phone`() {
        val profile = PhoneticProfile.fold(
            null,
            attempt(
                PhonemeMatch("ɹ", "-", MatchStatus.MISSED),
                PhonemeMatch("ɹ", "w", MatchStatus.MISSED),
            ),
        )
        val r = profile.stats.first { it.phoneme == "ɹ" }
        assertEquals(1, r.errors["(dropped)"])
        assertEquals(1, r.errors["w"])
        // Both are tied; typicalError is whichever the map orders first.
        assertTrue(r.typicalError == "w" || r.typicalError == "(dropped)")
    }

    @Test
    fun `the weakest sounds need enough attempts and come first`() {
        var profile = PhoneticProfile.fold(null, attempt(PhonemeMatch("θ", "s", MatchStatus.MISSED)))
        // Only one attempt: not yet eligible.
        assertTrue(PhoneticProfile.weakest(profile).isEmpty())

        repeat(PhoneticProfile.MIN_ATTEMPTS) {
            profile = PhoneticProfile.fold(profile, attempt(PhonemeMatch("θ", "s", MatchStatus.MISSED)))
        }
        repeat(PhoneticProfile.MIN_ATTEMPTS) {
            profile = PhoneticProfile.fold(profile, attempt(PhonemeMatch("æ", "æ", MatchStatus.PERFECT)))
        }

        val weakest = PhoneticProfile.weakest(profile)
        assertEquals("θ", weakest.first().phoneme)
        assertEquals(0, weakest.first().accuracy)
        assertTrue(weakest.none { it.phoneme == "æ" }, "a 100% sound must not be listed as weak")
    }
}
