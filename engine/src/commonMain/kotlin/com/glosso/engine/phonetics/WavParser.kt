package com.glosso.engine.phonetics

/**
 * Minimal WAV parsing.
 *
 * PCM is located by walking RIFF chunks instead of assuming a fixed 44-byte
 * header: real encoders add LIST/INFO/filler chunks, and treating those bytes as
 * audio shifts every sample and destroys recognition.
 */
internal fun findWavDataChunk(wav: ByteArray): Pair<Int, Int>? {
    val chunk = findChunk(wav, "data") ?: return null
    val length = minOf(chunk.size, (wav.size - chunk.start).toLong()).toInt()
    return if (length > 0) chunk.start to length else null
}

/** The `fmt ` fields the recognizer depends on. */
internal data class WavFormat(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
) {
    /**
     * 16-bit integer PCM at [sampleRate] 16 kHz, the only encoding the decoder
     * reads. Any channel count is accepted (it is downmixed). WAVE_FORMAT_EXTENSIBLE
     * is accepted at 16 bits: that is how some encoders label plain PCM.
     */
    val isDecodable: Boolean
        get() = (audioFormat == FORMAT_PCM || audioFormat == FORMAT_EXTENSIBLE) &&
            bitsPerSample == 16 && channels >= 1 && sampleRate == Wav2Vec2Audio.SAMPLE_RATE

    companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_EXTENSIBLE = 0xFFFE
    }
}

/** What the `fmt ` chunk of a buffer says, if anything. */
internal sealed interface WavFormatChunk {
    /** No format chunk: a headerless clip, decoded with the historical 16 kHz mono assumption. */
    data object Missing : WavFormatChunk

    /**
     * A format chunk shorter than the 16 bytes PCM needs, declared or actually
     * present. The channel count and rate are unknown, and guessing mono would
     * score misread samples with confidence, so the caller refuses the clip.
     */
    data object Truncated : WavFormatChunk

    data class Present(val format: WavFormat) : WavFormatChunk
}

internal fun findWavFormat(wav: ByteArray): WavFormatChunk {
    val chunk = findChunk(wav, "fmt ") ?: return WavFormatChunk.Missing
    if (chunk.size < 16 || chunk.start + 16 > wav.size) return WavFormatChunk.Truncated
    val p = chunk.start
    return WavFormatChunk.Present(
        WavFormat(
            audioFormat = u16(wav, p),
            channels = u16(wav, p + 2),
            sampleRate = u32(wav, p + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            bitsPerSample = u16(wav, p + 14),
        ),
    )
}

private class Chunk(val start: Int, val size: Long)

/**
 * Walks the RIFF chunks for [id]. Sizes are unsigned 32-bit and are read as Long,
 * so a corrupt or streaming size (0xFFFFFFFF) ends the walk instead of
 * overflowing into a negative offset and throwing out of the decoder. A zero-size
 * chunk (an empty JUNK/FLLR) is legal and skipped: the 8-byte header alone
 * guarantees progress.
 */
private fun findChunk(wav: ByteArray, id: String): Chunk? {
    if (wav.size < 12) return null
    var pos = 12L
    while (pos + 8 <= wav.size) {
        val at = pos.toInt()
        val size = u32(wav, at + 4)
        val dataStart = at + 8
        if (idMatches(wav, at, id)) return Chunk(dataStart, size)
        pos = dataStart + size + (size and 1L)
    }
    return null
}

/** Compares the 4-byte chunk id at [at] with the ASCII [id], byte by byte. */
private fun idMatches(wav: ByteArray, at: Int, id: String): Boolean {
    for (i in 0 until 4) if (wav[at + i].toInt() != id[i].code) return false
    return true
}

private fun u16(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

private fun u32(b: ByteArray, at: Int): Long =
    (b[at].toLong() and 0xff) or
        ((b[at + 1].toLong() and 0xff) shl 8) or
        ((b[at + 2].toLong() and 0xff) shl 16) or
        ((b[at + 3].toLong() and 0xff) shl 24)

/**
 * Little-endian 16-bit PCM to mono floats in [-1, 1), averaging interleaved
 * channels. A trailing partial frame is dropped.
 */
internal fun decodePcm16(wav: ByteArray, start: Int, length: Int, channels: Int): FloatArray {
    val frameBytes = 2 * channels.coerceAtLeast(1)
    val frames = length / frameBytes
    return FloatArray(frames) { f ->
        val base = start + f * frameBytes
        var sum = 0f
        for (c in 0 until frameBytes / 2) {
            val at = base + c * 2
            sum += ((wav[at].toInt() and 0xff) or ((wav[at + 1].toInt() and 0xff) shl 8)).toShort() / 32768f
        }
        sum / (frameBytes / 2)
    }
}
