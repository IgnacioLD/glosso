package com.glosso.app.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The shared WAV writer must produce exactly the bytes the Android recorder and
 * transcoder wrote with `ByteBuffer` before it, so a take scores the same.
 */
class WavParityTest {

    private fun legacy(pcmBytes: ByteArray, rate: Int = 16_000): ByteArray {
        val out = ByteArrayOutputStream(pcmBytes.size + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcmBytes.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcmBytes.size)
        out.write(header.array())
        out.write(pcmBytes)
        return out.toByteArray()
    }

    @Test
    fun `the shared writer matches the old ByteBuffer one`() {
        val samples = ShortArray(40_000) { ((it * 37) % 65_536 - 32_768).toShort() }
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        assertArrayEquals(bytes, Wav.bytesOf(samples))
        assertArrayEquals(legacy(bytes), Wav.pcm16Mono(bytes))
        assertArrayEquals(legacy(ByteArray(0)), Wav.pcm16Mono(ByteArray(0)))
    }
}
