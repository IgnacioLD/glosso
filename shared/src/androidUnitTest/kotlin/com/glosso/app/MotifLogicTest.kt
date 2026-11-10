package com.glosso.app

import com.glosso.engine.model.MasteryLevel
import com.glosso.engine.model.PhonemeErrorKindModel
import com.glosso.engine.model.PhonemeErrorModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The pure pieces behind the merged UI: one score-to-level mapping for every
 * screen, the collapsing header, the recorder halo, the reading progress, the
 * deck state and the localized date kicker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotifLogicTest {

    @Test
    fun `a bare accuracy reads with the engine's own bands`() {
        assertEquals(MasteryLevel.MASTERED, accuracyLevel(100))
        assertEquals(MasteryLevel.MASTERED, accuracyLevel(85))
        assertEquals(MasteryLevel.ALMOST, accuracyLevel(84))
        assertEquals(MasteryLevel.ALMOST, accuracyLevel(70))
        assertEquals(MasteryLevel.ROUGH, accuracyLevel(69))
        assertEquals(MasteryLevel.ROUGH, accuracyLevel(50))
        assertEquals(MasteryLevel.NOT_YET, accuracyLevel(49))
    }

    @Test
    fun `an out of range accuracy is clamped, never a new level`() {
        assertEquals(MasteryLevel.MASTERED, accuracyLevel(140))
        assertEquals(MasteryLevel.NOT_YET, accuracyLevel(-5))
    }

    @Test
    fun `the header collapses with the scroll and stays within 0 and 1`() {
        assertEquals(0f, collapseFraction(0, 100f), 0f)
        assertEquals(0.5f, collapseFraction(50, 100f), 0f)
        assertEquals(1f, collapseFraction(400, 100f), 0f)
        assertEquals(1f, collapseFraction(0, 0f), 0f)
    }

    @Test
    fun `a lazy list past its first item is fully collapsed`() {
        assertEquals(0.25f, collapseFraction(firstVisibleIndex = 0, firstVisibleOffsetPx = 25, distancePx = 100f), 0f)
        assertEquals(1f, collapseFraction(firstVisibleIndex = 1, firstVisibleOffsetPx = 0, distancePx = 100f), 0f)
    }

    @Test
    fun `the halo keeps the newest level first and a bounded history`() {
        var history = emptyList<Float>()
        repeat(10) { history = haloHistory(history, it / 10f, max = 4) }
        assertEquals(listOf(0.9f, 0.8f, 0.7f, 0.6f), history)
        assertEquals(listOf(1f, 0f), haloHistory(listOf(0f), 3f))
    }

    @Test
    fun `reading progress follows the scroll and a text that fits is unread`() {
        assertEquals(0f, readFraction(0, 0), 0f)
        assertEquals(0.5f, readFraction(200, 400), 0f)
        assertEquals(1f, readFraction(400, 400), 0f)
    }

    @Test
    fun `the deck offers a slow listen whenever there is audio, and own take only when it exists`() {
        val idle = deckState(AttemptState(), mapOf("en-US:normal" to "a.mp3"), playing = false)
        assertTrue(idle.hasAudio)
        // No rendered slow clip: the normal one is slowed by rate.
        assertTrue(idle.hasSlow)
        assertFalse(idle.hasTake)

        val taken = deckState(
            AttemptState(takePath = "/cache/take.wav", error = "x"),
            mapOf("en-US:normal" to "a.mp3", "en-US:slow" to "b.mp3"),
            playing = true,
        )
        assertTrue(taken.hasSlow)
        assertTrue(taken.hasTake)
        assertTrue(taken.playing)
        assertEquals("x", taken.error)

        assertFalse(deckState(AttemptState(), emptyMap(), playing = false).hasAudio)
        assertFalse(deckState(AttemptState(), emptyMap(), playing = false).hasSlow)
    }

    @Test
    fun `the date kicker follows each language's own order`() {
        val day = LocalDate.of(2026, 9, 25).toEpochDay()
        assertTrue(todayLine(day, "es"), todayLine(day, "es").contains("25 de septiembre"))
        assertTrue(todayLine(day, "en"), todayLine(day, "en").contains("September 25"))
        assertTrue(todayLine(day, "de"), todayLine(day, "de").contains("25. September"))
    }

    @Test
    fun `the same slip heard twice is diagnosed once, a different slip is kept`() {
        fun slip(actual: String?) = PhonemeErrorModel("θ", actual, PhonemeErrorKindModel.SUBSTITUTION, ruleId = "en.th.$actual")
        val errors = listOf(slip("s"), slip("s"), slip("t"), slip("s"))
        assertEquals(listOf(slip("s"), slip("t")), distinctDiagnoses(errors))
    }
}
