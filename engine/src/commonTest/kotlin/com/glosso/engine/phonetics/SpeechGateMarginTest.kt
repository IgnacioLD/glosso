package com.glosso.engine.phonetics

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The model hears the speech with a wide margin of quiet around it; whether a
 * clip is long and loud enough is judged on the speech itself.
 */
class SpeechGateMarginTest {

    private val rate = 16_000

    private fun clip(speechMs: Int, amplitude: Float, silenceMs: Int = 1_000): FloatArray {
        val silence = FloatArray(rate * silenceMs / 1000)
        val speech = FloatArray(rate * speechMs / 1000) { i -> amplitude * sin(2 * PI * 220 * i / rate).toFloat() }
        return silence + speech + silence
    }

    @Test
    fun `the model gets about 200 ms of quiet on each side of the speech`() {
        val trimmed = SpeechGate.trimSilence(clip(speechMs = 500, amplitude = 0.3f))

        val expected = rate * (500 + 2 * SpeechGate.MARGIN_MS) / 1000
        assertTrue(trimmed.size in (expected - 2 * 320)..(expected + 2 * 320), "kept ${trimmed.size} samples, expected about $expected")
    }

    @Test
    fun `a click is still too short - judged without the wide margin`() {
        val click = clip(speechMs = 20, amplitude = 0.5f)

        assertFalse(SpeechGate.isTooShort(SpeechGate.trimSilence(click).size), "with the model margin it would pass")
        assertTrue(SpeechGate.isTooShort(SpeechGate.trimSilence(click, marginMs = SpeechGate.GATE_MARGIN_MS).size))
    }

    @Test
    fun `a quiet word is not diluted into silence by the margin`() {
        val quiet = clip(speechMs = 300, amplitude = 0.01f)

        val speech = SpeechGate.trimSilence(quiet, marginMs = SpeechGate.GATE_MARGIN_MS)
        assertFalse(SpeechGate.isTooQuiet(SpeechGate.rms(speech)), "rms ${SpeechGate.rms(speech)}")
        assertTrue(SpeechGate.isTooQuiet(SpeechGate.rms(SpeechGate.trimSilence(quiet))), "the wide margin alone would reject it")
    }
}
