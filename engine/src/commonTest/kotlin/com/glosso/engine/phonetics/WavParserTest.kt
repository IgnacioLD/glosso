package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavParserTest {

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        require(id.length == 4)
        val pad = payload.size and 1
        val out = ByteArray(8 + payload.size + pad)
        id.forEachIndexed { i, c -> out[i] = c.code.toByte() }
        val size = payload.size
        out[4] = (size and 0xff).toByte()
        out[5] = ((size shr 8) and 0xff).toByte()
        out[6] = ((size shr 16) and 0xff).toByte()
        out[7] = ((size shr 24) and 0xff).toByte()
        payload.copyInto(out, 8)
        return out
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val header = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVE".encodeToByteArray()
        return header + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    private val fmt = chunk("fmt ", ByteArray(16))
    private val pcm = ByteArray(8) // 4 samples
    private val data = chunk("data", pcm)

    @Test
    fun `canonical fmt+data returns the data offset and length`() {
        val wav = riff(fmt, data)
        val (offset, length) = findWavDataChunk(wav)!!
        assertEquals(44, offset)
        assertEquals(pcm.size, length)
    }

    @Test
    fun `an extra chunk before data is skipped`() {
        val list = chunk("LIST", ByteArray(10))
        val wav = riff(fmt, list, data)
        val (offset, length) = findWavDataChunk(wav)!!
        assertEquals(44 + list.size, offset)
        assertEquals(pcm.size, length)
    }

    @Test
    fun `an odd-sized chunk is padded to an even boundary`() {
        val odd = chunk("JUNK", ByteArray(9))
        val wav = riff(fmt, odd, data)
        val (offset, _) = findWavDataChunk(wav)!!
        // The 9-byte payload is padded with one byte, so the chunk occupies 18.
        assertEquals(18, odd.size)
        assertEquals(44 + odd.size, offset)
    }

    @Test
    fun `a truncated data chunk is clamped to the buffer`() {
        val wav = riff(fmt, data).copyOf(48) // header says 8 PCM bytes, only 4 present
        val (offset, length) = findWavDataChunk(wav)!!
        assertEquals(44, offset)
        assertEquals(4, length)
    }

    @Test
    fun `a malformed or headerless buffer returns null`() {
        assertNull(findWavDataChunk(ByteArray(4)))
        assertNull(findWavDataChunk(riff(fmt))) // no data chunk
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())

    private fun le32(v: Long) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
    )

    private fun fmtOf(format: Int = 1, channels: Int = 1, rate: Int = 16_000, bits: Int = 16) = chunk(
        "fmt ",
        le16(format) + le16(channels) + le32(rate.toLong()) +
            le32(rate.toLong() * channels * bits / 8) + le16(channels * bits / 8) + le16(bits),
    )

    @Test
    fun `a corrupt chunk size ends the walk instead of throwing`() {
        // 0x7FFFFFFF used to overflow the next offset into a negative index.
        val huge = "JUNK".encodeToByteArray() + le32(0x7FFF_FFFFL) + ByteArray(4)
        assertNull(findWavDataChunk(riff(fmt, huge, data)))
        val unsigned = "JUNK".encodeToByteArray() + le32(0xFFFF_FFF0L) + ByteArray(4)
        assertNull(findWavDataChunk(riff(fmt, unsigned, data)))
    }

    @Test
    fun `a streaming data size reads to the end of the buffer`() {
        val streaming = "data".encodeToByteArray() + le32(0xFFFF_FFFFL) + pcm
        val (offset, length) = findWavDataChunk(riff(fmt, streaming))!!
        assertEquals(44, offset)
        assertEquals(pcm.size, length)
    }

    @Test
    fun `the format chunk is read and only 16-bit pcm at 16 kHz is decodable`() {
        fun formatOf(wav: ByteArray) = (findWavFormat(wav) as WavFormatChunk.Present).format
        val format = formatOf(riff(fmtOf(), data))
        assertEquals(WavFormat(audioFormat = 1, channels = 1, sampleRate = 16_000, bitsPerSample = 16), format)
        assertTrue(format.isDecodable)
        assertTrue(formatOf(riff(fmtOf(channels = 2), data)).isDecodable)
        assertTrue(formatOf(riff(fmtOf(format = 0xFFFE), data)).isDecodable)
        assertFalse(formatOf(riff(fmtOf(rate = 44_100), data)).isDecodable)
        assertFalse(formatOf(riff(fmtOf(bits = 8), data)).isDecodable)
        assertFalse(formatOf(riff(fmtOf(format = 3, bits = 32), data)).isDecodable)
        assertEquals(WavFormatChunk.Missing, findWavFormat(riff(data)))
    }

    @Test
    fun `an empty chunk before data or fmt does not end the walk`() {
        // Zero-size chunks are legal RIFF; stopping at one made the recognizer fall
        // back to decoding the header and fmt bytes as PCM.
        val empty = chunk("JUNK", ByteArray(0))
        val wav = riff(empty, fmtOf(channels = 2), chunk("FLLR", ByteArray(0)), data)
        val (offset, length) = findWavDataChunk(wav)!!
        assertEquals(12 + 8 + 24 + 8 + 8, offset)
        assertEquals(pcm.size, length)
        assertEquals(2, (findWavFormat(wav) as WavFormatChunk.Present).format.channels)
    }

    @Test
    fun `a truncated fmt chunk is reported and not read as mono`() {
        // Declared shorter than the 16 bytes PCM needs.
        assertEquals(WavFormatChunk.Truncated, findWavFormat(riff(chunk("fmt ", ByteArray(12)), data)))
        // Declared complete but cut off by the end of the buffer.
        val cut = riff(fmtOf()).copyOf(12 + 8 + 10)
        assertEquals(WavFormatChunk.Truncated, findWavFormat(cut))
    }

    @Test
    fun `mono pcm decodes sample by sample and stereo is downmixed`() {
        val mono = le16(16_384) + le16(-16_384 and 0xffff) + le16(0x7fff)
        assertEquals(listOf(0.5f, -0.5f, 32_767f / 32_768f), decodePcm16(mono, 0, mono.size, 1).toList())

        val stereo = le16(16_384) + le16(-16_384 and 0xffff) + le16(16_384) + le16(16_384) + le16(1)
        // Two full frames; the trailing half frame is dropped.
        assertEquals(listOf(0f, 0.5f), decodePcm16(stereo, 0, stereo.size, 2).toList())
    }
}
