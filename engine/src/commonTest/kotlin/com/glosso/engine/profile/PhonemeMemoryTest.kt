package com.glosso.engine.profile

import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PhonemeObservationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhonemeMemoryTest {

    private fun match(expected: String, actual: String, status: MatchStatusModel) =
        PhonemeMatchModel(expected, actual, status)

    @Test
    fun `one attempt collapses to one record per sound`() {
        val records = PhonemeMemory.observationsOf(
            listOf(
                match("θ", "θ", MatchStatusModel.PERFECT),
                match("θ", "s", MatchStatusModel.MISSED),
                match("æ", "ɛ", MatchStatusModel.CLOSE),
            ),
            day = 100,
        )

        assertEquals(2, records.size)
        val theta = records.first { it.phoneme == "θ" }
        assertEquals(2, theta.total)
        assertEquals(1, theta.correct)
        assertEquals(50, theta.accuracy)
        assertEquals("s", theta.error)

        val ae = records.first { it.phoneme == "æ" }
        assertEquals(0, ae.correct)
        assertEquals("ɛ", ae.error)
    }

    @Test
    fun `two different errors of the same sound are two different patterns`() {
        val history = PhonemeMemory.record(
            emptyList(),
            listOf(match("θ", "s", MatchStatusModel.MISSED)),
            day = 1,
        )
        val other = PhonemeMemory.record(
            emptyList(),
            listOf(match("θ", "t", MatchStatusModel.MISSED)),
            day = 1,
        )

        val thToS = history.single().pattern
        val thToT = other.single().pattern
        assertEquals("en.th.stopping", thToS)
        assertEquals("en.th.stopping.t", thToT)
        assertTrue(thToS != thToT, "different errors must not collapse into one pattern")
    }

    @Test
    fun `a dropped sound gets the deletion pattern`() {
        val dropped = PhonemeMemory.record(
            emptyList(),
            listOf(match("h", "-", MatchStatusModel.MISSED)),
            day = 1,
        ).single()
        assertEquals("en.h.dropping", dropped.pattern)
    }

    @Test
    fun `an uncurated contrast is recorded without a pattern`() {
        val record = PhonemeMemory.record(
            emptyList(),
            listOf(match("θ", "x", MatchStatusModel.MISSED)),
            day = 1,
        ).single()
        assertEquals("x", record.error)
        assertEquals(null, record.pattern)
    }

    @Test
    fun `a fully landed sound records no error`() {
        val records = PhonemeMemory.observationsOf(
            listOf(match("θ", "θ", MatchStatusModel.PERFECT)),
            day = 1,
        )
        assertNull(records.single().error)
        assertTrue(records.single().landed)
    }

    @Test
    fun `a dropped sound is recorded distinctly`() {
        val records = PhonemeMemory.observationsOf(
            listOf(match("ɹ", "-", MatchStatusModel.MISSED)),
            day = 1,
        )
        assertEquals(PhonemeMemory.DROPPED, records.single().error)
        assertTrue(!records.single().landed)
    }

    @Test
    fun `history accumulates in chronological order`() {
        var history = emptyList<PhonemeObservationModel>()
        history = PhonemeMemory.record(history, listOf(match("θ", "s", MatchStatusModel.MISSED)), day = 10)
        history = PhonemeMemory.record(history, listOf(match("θ", "θ", MatchStatusModel.PERFECT)), day = 20)

        val series = PhonemeMemory.series(history, "θ")
        assertEquals(listOf(10L, 20L), series.map { it.day })
        assertEquals(0, series[0].accuracy)
        assertEquals(100, series[1].accuracy)
    }

    @Test
    fun `a sound's history is bounded`() {
        var history = emptyList<PhonemeObservationModel>()
        repeat(PhonemeMemory.MAX_PER_PHONEME + 25) { i ->
            history = PhonemeMemory.record(
                history,
                listOf(match("θ", "s", MatchStatusModel.MISSED)),
                day = i.toLong(),
            )
        }
        val series = PhonemeMemory.series(history, "θ")
        assertEquals(PhonemeMemory.MAX_PER_PHONEME, series.size)
        // The oldest records are the ones dropped.
        assertEquals((PhonemeMemory.MAX_PER_PHONEME + 24).toLong(), series.last().day)
    }

    @Test
    fun `an alignment with nothing scoreable leaves history untouched`() {
        val history = listOf(PhonemeObservationModel("θ", day = 1, total = 1, correct = 0))
        val same = PhonemeMemory.record(
            history,
            listOf(match("-", "-", MatchStatusModel.PERFECT)),
            day = 2,
        )
        assertEquals(history, same)
    }
}
