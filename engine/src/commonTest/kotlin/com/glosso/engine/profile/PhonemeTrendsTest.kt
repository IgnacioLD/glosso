package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeObservationModel
import kotlin.test.Test
import kotlin.test.assertEquals

class PhonemeTrendsTest {

    private fun obs(day: Long, accuracy: Int, phoneme: String = "θ") =
        PhonemeObservationModel(phoneme = phoneme, day = day, total = 100, correct = accuracy)

    @Test
    fun `daily points average several attempts on the same day`() {
        val points = PhonemeTrends.daily(
            listOf(obs(1, 40), obs(1, 60), obs(2, 50)),
        )
        assertEquals(listOf(50, 50), points.map { it.accuracy })
        assertEquals(2, points[0].attempts)
    }

    @Test
    fun `rising numbers are improving`() {
        val trend = PhonemeTrends.of(
            listOf(obs(1, 43), obs(3, 51), obs(7, 63), obs(14, 71)),
            "θ",
        )
        assertEquals(TrendDirection.IMPROVING, trend.direction)
        assertEquals(28, trend.delta)
        assertEquals(43, trend.firstAccuracy)
        assertEquals(71, trend.latestAccuracy)
        assertEquals(4, trend.days)
    }

    @Test
    fun `falling numbers are declining`() {
        val trend = PhonemeTrends.of(
            listOf(obs(1, 71), obs(3, 63), obs(7, 51), obs(14, 43)),
            "θ",
        )
        assertEquals(TrendDirection.DECLINING, trend.direction)
    }

    @Test
    fun `small wobble is stable`() {
        val trend = PhonemeTrends.of(
            listOf(obs(1, 50), obs(2, 52), obs(3, 48), obs(4, 51)),
            "θ",
        )
        assertEquals(TrendDirection.STABLE, trend.direction)
    }

    @Test
    fun `a single day is not a trend yet`() {
        val trend = PhonemeTrends.of(listOf(obs(1, 43)), "θ")
        assertEquals(TrendDirection.UNKNOWN, trend.direction)
    }

    @Test
    fun `today delta compares the day against the one before`() {
        val history = listOf(obs(1, 43), obs(3, 51), obs(7, 63), obs(14, 71))
        assertEquals(8, PhonemeTrends.todayDelta(history, "θ", 14))
    }

    @Test
    fun `today delta averages several attempts of the day`() {
        val history = listOf(obs(7, 63), obs(14, 65), obs(14, 75))
        assertEquals(7, PhonemeTrends.todayDelta(history, "θ", 14))
    }

    @Test
    fun `a sound first seen today has no delta yet`() {
        val history = listOf(obs(14, 60))
        assertEquals(0, PhonemeTrends.todayDelta(history, "θ", 14))
    }

    @Test
    fun `no measurement today means no delta`() {
        val history = listOf(obs(1, 43), obs(7, 63))
        assertEquals(null, PhonemeTrends.todayDelta(history, "θ", 14))
    }

    @Test
    fun `today delta is per sound`() {
        val history = listOf(obs(7, 63), obs(14, 71), obs(7, 80, "ð"), obs(14, 70, "ð"))
        assertEquals(8, PhonemeTrends.todayDelta(history, "θ", 14))
        assertEquals(-10, PhonemeTrends.todayDelta(history, "ð", 14))
    }

    @Test
    fun `a single noisy point cannot flip the direction`() {
        // Drops at the very end, but the three-point windows still rise.
        val trend = PhonemeTrends.of(
            listOf(obs(1, 40), obs(2, 45), obs(3, 55), obs(4, 70), obs(5, 50)),
            "θ",
        )
        assertEquals(TrendDirection.IMPROVING, trend.direction)
    }
}
