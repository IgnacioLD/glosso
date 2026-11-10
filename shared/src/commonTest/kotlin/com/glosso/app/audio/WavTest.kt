package com.glosso.app.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WavTest {

    @Test
    fun `the header is the canonical 44-byte PCM header`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = Wav.pcm16Mono(pcm)
        val expected = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            40, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0,
            1, 0,
            1, 0,
            0x80.toByte(), 0x3E, 0, 0, // 16000
            0x00, 0x7D, 0, 0, // 32000 bytes per second
            2, 0,
            16, 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            4, 0, 0, 0,
            1, 2, 3, 4,
        )
        assertContentEquals(expected, wav)
    }

    @Test
    fun `samples are written little-endian`() {
        assertContentEquals(byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte()), Wav.bytesOf(shortArrayOf(0x1234, -1)))
    }

    @Test
    fun `the peak is the loudest sample`() {
        assertEquals(0.5f, Wav.peak(Wav.bytesOf(shortArrayOf(100, -16384, 200))))
        assertEquals(0f, Wav.peak(ByteArray(0)))
    }
}
