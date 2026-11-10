package com.glosso.engine.phonetics

import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneticLoopTest {

    private fun match(expected: String, actual: String, status: MatchStatusModel) =
        PhonemeMatchModel(expected, actual, status)

    @Test
    fun `a perfect take scores every sound at the top`() {
        val breakdown = PhoneticLoop.breakdown(
            listOf(
                match("θ", "θ", MatchStatusModel.PERFECT),
                match("eɪ", "eɪ", MatchStatusModel.PERFECT),
            ),
        )

        assertEquals(listOf(100, 100), breakdown.map { it.score })
    }

    @Test
    fun `a missed sound earns nothing and ranks first`() {
        val breakdown = PhoneticLoop.breakdown(
            listOf(
                match("r", "r", MatchStatusModel.PERFECT),
                match("ð", "d", MatchStatusModel.MISSED),
            ),
        )

        assertEquals("ð", breakdown.first().phoneme)
        assertEquals(0, breakdown.first().score)
        assertEquals("r", breakdown.last().phoneme)
    }

    @Test
    fun `a close sound sits between missed and perfect`() {
        val breakdown = PhoneticLoop.breakdown(
            listOf(match("θ", "s", MatchStatusModel.CLOSE)),
        )

        assertEquals(PhoneticLoop.CLOSE_SCORE, breakdown.single().score)
    }

    @Test
    fun `repeated sounds average into one entry`() {
        val breakdown = PhoneticLoop.breakdown(
            listOf(
                match("θ", "θ", MatchStatusModel.PERFECT),
                match("θ", "s", MatchStatusModel.MISSED),
                match("θ", "θ", MatchStatusModel.PERFECT),
            ),
        )

        val theta = breakdown.single()
        assertEquals(67, theta.score)
        assertEquals(2, theta.correct)
        assertEquals(3, theta.total)
    }

    @Test
    fun `inserted phones are ignored`() {
        val breakdown = PhoneticLoop.breakdown(
            listOf(
                match("-", "e", MatchStatusModel.MISSED),
                match("s", "s", MatchStatusModel.PERFECT),
            ),
        )

        assertEquals(listOf("s"), breakdown.map { it.phoneme })
    }

    @Test
    fun `profile score ignores vowel length`() {
        val profile = PhoneticProfileModel(
            stats = listOf(PhonemeStatModel("i", attempts = 4, correct = 1)),
        )

        assertEquals(25, PhoneticLoop.profileScore(profile, "iː"))
        assertNull(PhoneticLoop.profileScore(profile, "θ"))
    }

    @Test
    fun `overall weighs every attempt - not every sound`() {
        val profile = PhoneticProfileModel(
            stats = listOf(
                PhonemeStatModel("θ", attempts = 3, correct = 0),
                PhonemeStatModel("s", attempts = 1, correct = 1),
            ),
        )

        assertEquals(25, PhoneticLoop.overall(profile))
    }

    @Test
    fun `delta and improvement only speak when both ends exist`() {
        assertEquals(7, PhoneticLoop.delta(67, 74))
        assertNull(PhoneticLoop.delta(null, 74))
        assertTrue(PhoneticLoop.improved(67, 74))
        assertEquals(false, PhoneticLoop.improved(74, 74))
    }
}
