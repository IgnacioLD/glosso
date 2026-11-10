package com.glosso.engine.phonetics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProsodyAnalyzerTest {

    private val sampleRate = 16_000

    private fun sine(frequency: Float, milliseconds: Int, amplitude: Float = 0.5f): FloatArray {
        val n = sampleRate * milliseconds / 1000
        return FloatArray(n) { i ->
            (amplitude * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
        }
    }

    @Test
    fun `duration and energy are measured over the span`() {
        val pcm = sine(200f, 200)
        val prosody = ProsodyAnalyzer.measure(pcm, sampleRate, 0, pcm.size)

        assertEquals(200, prosody.durationMs)
        // RMS of a 0.5-amplitude sine is 0.5/sqrt(2) ~= 0.354.
        assertTrue(abs(prosody.energy - 0.354f) < 0.02f, "energy was ${prosody.energy}")
        assertTrue(prosody.voiced, "a clean tone must be voiced")
    }

    @Test
    fun `pitch of a pure tone is recovered`() {
        val pcm = sine(200f, 200)
        val f0 = ProsodyAnalyzer.pitch(pcm, sampleRate, 0, pcm.size)
        assertNotNull(f0)
        assertTrue(abs(f0 - 200f) < 8f, "expected ~200 Hz, got $f0")
    }

    @Test
    fun `silence has no pitch`() {
        val silence = FloatArray(sampleRate / 5)
        val prosody = ProsodyAnalyzer.measure(silence, sampleRate, 0, silence.size)
        assertNull(prosody.f0Hz)
        assertTrue(!prosody.voiced)
        assertEquals(0f, prosody.energy)
    }

    @Test
    fun `noise has energy but no stable pitch`() {
        // A deterministic pseudo-random signal: loud, but aperiodic.
        var seed = 12345
        val noise = FloatArray(sampleRate / 5) {
            seed = seed * 1103515245 + 12345
            ((seed shr 16) and 0x7fff) / 16384f - 1f
        }
        val prosody = ProsodyAnalyzer.measure(noise, sampleRate, 0, noise.size)
        assertTrue(prosody.energy > 0.1f, "noise should be loud, got ${prosody.energy}")
        assertNull(prosody.f0Hz, "noise must not produce a pitch")
    }

    @Test
    fun `a span shorter than a pitch period is not voiced`() {
        val pcm = sine(200f, 200)
        val prosody = ProsodyAnalyzer.measure(pcm, sampleRate, 0, sampleRate / 100)
        assertNull(prosody.f0Hz)
    }
}
