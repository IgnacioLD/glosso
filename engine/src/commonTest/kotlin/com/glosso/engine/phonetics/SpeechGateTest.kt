package com.glosso.engine.phonetics

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class SpeechGateTest {

    // region rms

    @Test
    fun `rms of silence is zero`() {
        assertEquals(0f, SpeechGate.rms(FloatArray(1000)), 1e-6f)
    }

    @Test
    fun `rms of empty input is zero`() {
        assertEquals(0f, SpeechGate.rms(FloatArray(0)), 1e-6f)
    }

    @Test
    fun `rms of a full-scale square wave is one`() {
        val pcm = FloatArray(1000) { if (it % 2 == 0) 1f else -1f }
        assertEquals(1f, SpeechGate.rms(pcm), 1e-4f)
    }

    @Test
    fun `rms of a constant amplitude signal equals that amplitude`() {
        val pcm = FloatArray(1000) { if (it % 2 == 0) 0.5f else -0.5f }
        assertEquals(0.5f, SpeechGate.rms(pcm), 1e-4f)
    }

    // endregion

    // region trimSilence

    private fun tone(samples: Int, amplitude: Float, offset: Int = 0): FloatArray =
        FloatArray(samples) { i ->
            val phase = ((offset + i) % 40) / 40f
            amplitude * (2f * phase - 1f)
        }

    @Test
    fun `pure silence trims to nothing`() {
        assertTrue(SpeechGate.trimSilence(FloatArray(16_000)).isEmpty())
    }

    @Test
    fun `near-silent room tone trims to nothing`() {
        val pcm = tone(16_000, 0.0002f)
        assertTrue(SpeechGate.trimSilence(pcm).isEmpty())
    }

    @Test
    fun `leading and trailing silence is removed`() {
        val silence = FloatArray(16_000)
        val speech = tone(16_000, 0.3f)
        val trimmed = SpeechGate.trimSilence(silence + speech + silence)

        // Must drop most of the silence, and keep a bit of margin either side.
        assertTrue(trimmed.size < 16_000 + 8_000, "expected trimming, got ${trimmed.size}")
        assertTrue(trimmed.size >= 16_000, "expected to keep the speech, got ${trimmed.size}")
        // The speech itself must come through intact (a sawtooth has ~0.577 * amplitude RMS).
        assertTrue(
            SpeechGate.rms(trimmed) > SpeechGate.rms(speech) * 0.8f,
            "speech level should survive, got ${SpeechGate.rms(trimmed)}"
        )
    }

    @Test
    fun `speech with no silence is kept`() {
        val speech = tone(16_000, 0.3f)
        val trimmed = SpeechGate.trimSilence(speech)
        assertEquals(speech.size, trimmed.size)
    }

    @Test
    fun `empty input is handled`() {
        assertTrue(SpeechGate.trimSilence(FloatArray(0)).isEmpty())
    }

    // endregion

    // region gates

    @Test
    fun `short clips are rejected`() {
        assertTrue(SpeechGate.isTooShort(SpeechGate.MIN_SAMPLES - 1))
        assertFalse(SpeechGate.isTooShort(SpeechGate.MIN_SAMPLES))
    }

    @Test
    fun `quiet clips are rejected`() {
        assertTrue(SpeechGate.isTooQuiet(0f))
        assertTrue(SpeechGate.isTooQuiet(SpeechGate.MIN_RMS - 0.0001f))
        assertFalse(SpeechGate.isTooQuiet(SpeechGate.MIN_RMS))
        assertFalse(SpeechGate.isTooQuiet(0.05f))
    }

    @Test
    fun `silent clip is below the quiet threshold`() {
        // A near-silent room recording: tiny amplitude noise.
        val pcm = FloatArray(16_000) { if (it % 2 == 0) 0.0004f else -0.0004f }
        assertTrue(SpeechGate.isTooQuiet(SpeechGate.rms(pcm)))
    }

    @Test
    fun `speech-level clip passes the quiet threshold`() {
        val pcm = FloatArray(16_000) { if (it % 2 == 0) 0.08f else -0.08f }
        assertFalse(SpeechGate.isTooQuiet(SpeechGate.rms(pcm)))
    }

    @Test
    fun `too few voiced frames is not speech-like`() {
        assertFalse(SpeechGate.isSpeechLike(voicedFrames = 0, meanConfidence = 1f))
        assertFalse(
            SpeechGate.isSpeechLike(
                voicedFrames = SpeechGate.MIN_SHORT_VOICED_FRAMES - 1,
                meanConfidence = 1f
            )
        )
    }

    @Test
    fun `a short but confident clip is speech-like`() {
        // A half-second word lands in two to four frames at the model's frame rate,
        // and silence never produces near-certain frames. The old flat frame-count
        // floor rejected the reference audio of short words.
        assertTrue(SpeechGate.isSpeechLike(voicedFrames = 2, meanConfidence = 0.9f))
        assertTrue(SpeechGate.isSpeechLike(voicedFrames = 4, meanConfidence = 0.8f))
        // Few confident frames are not enough when the confidence is only average.
        assertFalse(SpeechGate.isSpeechLike(voicedFrames = 4, meanConfidence = 0.35f))
    }

    @Test
    fun `low confidence is not speech-like`() {
        assertFalse(
            SpeechGate.isSpeechLike(
                voicedFrames = 100,
                meanConfidence = SpeechGate.MIN_MEAN_CONFIDENCE - 0.01f
            )
        )
    }

    @Test
    fun `enough confident voiced frames is speech-like`() {
        assertTrue(SpeechGate.isSpeechLike(voicedFrames = 100, meanConfidence = 0.8f))
        assertTrue(
            SpeechGate.isSpeechLike(
                voicedFrames = SpeechGate.MIN_VOICED_FRAMES,
                meanConfidence = SpeechGate.MIN_MEAN_CONFIDENCE
            )
        )
    }

    // endregion
}
