package com.glosso.app.audio

import kotlin.math.abs

/** The one audio format the recognizer accepts: 16 kHz, mono, 16-bit PCM in a WAV file. */
object Wav {

    const val SAMPLE_RATE = 16_000

    /** A complete WAV file (44-byte header) around little-endian 16-bit mono [pcm]. */
    fun pcm16Mono(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val out = ByteArray(HEADER + pcm.size)
        var at = 0
        fun ascii(text: String) = text.forEach { out[at++] = it.code.toByte() }
        fun int32(value: Int) = repeat(4) { out[at++] = (value shr (8 * it)).toByte() }
        fun int16(value: Int) = repeat(2) { out[at++] = (value shr (8 * it)).toByte() }
        ascii("RIFF")
        int32(36 + pcm.size)
        ascii("WAVE")
        ascii("fmt ")
        int32(16)
        int16(1)
        int16(1)
        int32(sampleRate)
        int32(sampleRate * 2)
        int16(2)
        int16(16)
        ascii("data")
        int32(pcm.size)
        pcm.copyInto(out, at)
        return out
    }

    /** [samples] as little-endian bytes. */
    fun bytesOf(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            out[2 * i] = sample.toInt().toByte()
            out[2 * i + 1] = (sample.toInt() shr 8).toByte()
        }
        return out
    }

    /**
     * Float samples at [fromRate] (a browser microphone, usually 48 kHz) as
     * little-endian 16-bit PCM at [SAMPLE_RATE]. Each output sample averages the
     * input it spans, which keeps the speech band and drops most of what would
     * alias above 8 kHz.
     */
    fun downsampleToPcm16(samples: FloatArray, fromRate: Int): ByteArray {
        val ratio = fromRate.toDouble() / SAMPLE_RATE
        val count = (samples.size / ratio).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val start = (i * ratio).toInt()
            val end = minOf(samples.size, maxOf(start + 1, ((i + 1) * ratio).toInt()))
            var sum = 0.0
            for (j in start until end) sum += samples[j]
            out[i] = ((sum / (end - start)).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return bytesOf(out)
    }

    /** Peak absolute sample in the first [length] bytes of [bytes], 0..1, little-endian 16-bit PCM. */
    fun peak(bytes: ByteArray, length: Int = bytes.size): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)
            val value = abs(sample.toShort().toInt())
            if (value > peak) peak = value
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private const val HEADER = 44
}
