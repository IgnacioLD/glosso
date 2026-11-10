package com.glosso.engine.phonetics

import kotlin.math.sqrt

/** Measured acoustics of one phone: how long, how loud, and whether it was voiced. */
data class PhoneProsody(
    val durationMs: Int,
    val energy: Float,
    val f0Hz: Float?,
    val voiced: Boolean,
)

/**
 * Measures the acoustic correlates of prosody inside a time span: duration,
 * energy and the fundamental frequency (pitch). Duration and pitch carry the
 * stress, rhythm and intonation that the phoneme comparison deliberately ignores,
 * so a learner who says all the right sounds with the wrong stress can still be
 * told exactly what to fix.
 *
 * Everything here is pure DSP over float PCM, so it runs identically on the JVM
 * and on Android without a model.
 */
object ProsodyAnalyzer {

    /** Below this RMS the span is too quiet for a pitch estimate to mean anything. */
    const val MIN_ENERGY = 0.01f

    private const val MIN_F0 = 70f
    private const val MAX_F0 = 350f
    private const val WINDOW_MS = 40
    private const val HOP_MS = 20

    /** Shortest span that can hold a stable pitch estimate (~35 ms). */
    private const val MIN_F0_SAMPLES_MS = 35

    /** Minimum normalised autocorrelation for a window to count as voiced. */
    private const val MIN_PERIODICITY = 0.35f

    /** A span is voiced when at least this many windows show a clear period. */
    private const val MIN_VOICED_WINDOWS = 2

    fun measure(pcm: FloatArray, sampleRate: Int, startSample: Int, endSample: Int): PhoneProsody {
        val start = startSample.coerceIn(0, pcm.size)
        val end = endSample.coerceIn(start, pcm.size)
        val durationMs = if (sampleRate <= 0) 0 else ((end - start) * 1000 / sampleRate)
        val energy = rms(pcm, start, end)
        val f0 = if (energy >= MIN_ENERGY) pitch(pcm, sampleRate, start, end) else null
        return PhoneProsody(durationMs = durationMs, energy = energy, f0Hz = f0, voiced = f0 != null)
    }

    /**
     * Median fundamental frequency over the span, or null when the signal is too
     * short or too aperiodic to have one.
     */
    fun pitch(pcm: FloatArray, sampleRate: Int, startSample: Int, endSample: Int): Float? {
        if (sampleRate <= 0) return null
        val window = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        val hop = (sampleRate * HOP_MS / 1000).coerceAtLeast(1)
        val minLength = sampleRate * MIN_F0_SAMPLES_MS / 1000
        val span = endSample - startSample
        if (span < minLength) return null

        val estimates = mutableListOf<Float>()
        var from = startSample
        while (from + minLength <= endSample) {
            val length = minOf(window, endSample - from)
            estimateF0(pcm, from, length, sampleRate)?.let { estimates.add(it) }
            from += hop
        }
        if (estimates.size < MIN_VOICED_WINDOWS) return null
        return estimates.sorted()[estimates.size / 2]
    }

    /**
     * Autocorrelation pitch for one window. Returns null when the best period is
     * too weak to be a voice (unvoiced fricative, noise, silence).
     */
    fun estimateF0(pcm: FloatArray, from: Int, length: Int, sampleRate: Int): Float? {
        val minLag = (sampleRate / MAX_F0).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / MIN_F0).toInt()
        if (maxLag <= minLag || from < 0 || from + length > pcm.size) return null

        var mean = 0.0
        for (i in 0 until length) mean += pcm[from + i]
        mean /= length

        var energy = 0.0
        for (i in 0 until length) {
            val v = pcm[from + i] - mean
            energy += v * v
        }
        if (energy <= 1e-9) return null

        var bestLag = -1
        var bestValue = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            val limit = length - lag
            var i = 0
            while (i < limit) {
                sum += (pcm[from + i] - mean) * (pcm[from + i + lag] - mean)
                i++
            }
            val normalized = sum / energy
            if (normalized > bestValue) {
                bestValue = normalized
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestValue < MIN_PERIODICITY) return null
        return sampleRate.toFloat() / bestLag
    }

    private fun rms(pcm: FloatArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var sum = 0.0
        for (i in start until end) sum += pcm[i].toDouble() * pcm[i]
        return sqrt(sum / (end - start)).toFloat()
    }
}
