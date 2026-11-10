package com.glosso.engine.phonetics.cheap

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cheap, model-free segmentation: where speech, voicing and syllable nuclei are.
 *
 * Port of `benchmark/cheap-scorers/cheap/segmenter.py` (PR #43). It deliberately
 * does not solve full phone alignment: it produces the robust anchors the cheap
 * probes need (speech span, voiced-energy nuclei, and the onset before the first
 * nucleus). The reference is the one the parity test pins down.
 */
internal object CheapSegmenter {

    private val PHONE_CLASS = mapOf(
        "p" to "stop", "b" to "stop", "t" to "stop", "d" to "stop", "k" to "stop",
        "g" to "stop", "ɡ" to "stop", "ʔ" to "stop",
        "f" to "fric", "v" to "fric", "θ" to "fric", "ð" to "fric",
        "s" to "fric", "z" to "fric", "ʃ" to "fric", "ʒ" to "fric", "h" to "fric",
        "m" to "nasal", "n" to "nasal", "ŋ" to "nasal",
        "l" to "approx", "ɹ" to "approx", "r" to "approx", "w" to "approx",
        "j" to "approx", "ʍ" to "approx",
        "i" to "vowel", "ɪ" to "vowel", "e" to "vowel", "ɛ" to "vowel", "æ" to "vowel",
        "a" to "vowel", "ɑ" to "vowel", "ɒ" to "vowel", "ɔ" to "vowel", "o" to "vowel",
        "ʊ" to "vowel", "u" to "vowel", "ʌ" to "vowel", "ə" to "vowel", "ɚ" to "vowel",
        "ɜ" to "vowel", "aɪ" to "vowel", "aʊ" to "vowel", "ɔɪ" to "vowel", "eɪ" to "vowel",
        "oʊ" to "vowel", "ɪə" to "vowel", "ɛə" to "vowel", "ʊə" to "vowel",
    )

    fun phoneClass(phone: String): String =
        PHONE_CLASS[phone.removeSuffix("ː")] ?: "other"

    private fun close(active: BooleanArray, k: Int): BooleanArray {
        val n = active.size
        val radius = k / 2
        val dilated = BooleanArray(n)
        for (i in 0 until n) {
            var any = false
            for (d in -radius..radius) {
                val j = i + d
                if (j in 0 until n && active[j]) {
                    any = true
                    break
                }
            }
            dilated[i] = any
        }
        val eroded = BooleanArray(n)
        for (i in 0 until n) {
            var all = true
            for (d in -radius..radius) {
                val j = i + d
                if (j !in 0 until n || !dilated[j]) {
                    all = false
                    break
                }
            }
            eroded[i] = all
        }
        return eroded
    }

    /** Frame-level acoustic tracks at a fixed hop (10 ms by default). */
    class Tracks(val pcm: DoubleArray, val sampleRate: Int, hopMs: Double = 10.0, winMs: Double = 25.0) {
        val win: Int = (sampleRate * winMs / 1000).roundToInt()
        val hop: Int = (sampleRate * hopMs / 1000).roundToInt()
        val hopSeconds: Double = hop.toDouble() / sampleRate

        val frames: Array<DoubleArray> = CheapDsp.frameSignal(pcm, win, hop)
        val rms: DoubleArray = CheapDsp.frameRms(frames)
        val rmsDb: DoubleArray = DoubleArray(rms.size) { 20.0 * log10(rms[it] + CheapDsp.EPS) }
        val zcr: DoubleArray = CheapDsp.frameZcr(frames)

        private val mag = CheapDsp.spectralFrames(frames, sampleRate, 512)
        private val moments = CheapDsp.spectralMoments(mag, sampleRate, 512)
        val centroid = moments.centroid
        val spread = moments.spread
        val skew = moments.skew
        val kurtosis = moments.kurtosis
        val flatness = moments.flatness
        private val bands = CheapDsp.bandEnergy(mag, sampleRate, 512)
        val highRatio: DoubleArray = DoubleArray(rms.size) {
            (bands["band_2000_4000"]?.get(it) ?: 0.0) + (bands["band_4000_8000"]?.get(it) ?: 0.0)
        }

        private val f0Track: CheapDsp.F0Track =
            CheapDsp.trackF0(pcm, sampleRate, winMs = max(40.0, winMs), hopMs = hopMs)
        val f0: DoubleArray
        val clarity: DoubleArray

        init {
            val idx = CheapDsp.linspace(0.0, (f0Track.f0.size - 1).toDouble(), rms.size)
            val xp = DoubleArray(f0Track.f0.size) { it.toDouble() }
            f0 = CheapDsp.interp(idx, xp, f0Track.f0)
            clarity = CheapDsp.interp(idx, xp, f0Track.clarity)
        }

        val nFrames: Int = rms.size
        val floorDb: Double = CheapDsp.percentile(rmsDb, 20.0)
        val peakDb: Double = CheapDsp.percentile(rmsDb, 99.0)
        val voiced: BooleanArray = BooleanArray(nFrames) { clarity[it] > 0.45 && f0[it] > 55 && f0[it] < 400 }
        val venergy: DoubleArray = DoubleArray(nFrames) { if (voiced[it]) rmsDb[it] else Double.NEGATIVE_INFINITY }

        fun spanSeconds(lo: Int, hi: Int): Double = max(0, hi - lo) * hopSeconds

        fun sliceStat(values: DoubleArray, lo: Int, hi: Int, reduce: String = "mean"): Double {
            val a = max(0, lo)
            val b = minOf(nFrames, hi)
            if (b <= a) return Double.NaN
            val seg = values.copyOfRange(a, b).filter { it.isFinite() }
            if (seg.isEmpty()) return Double.NaN
            return when (reduce) {
                "mean" -> seg.average()
                "median" -> CheapDsp.percentile(seg.toDoubleArray(), 50.0)
                "max" -> seg.max()
                "min" -> seg.min()
                "std" -> {
                    val m = seg.average()
                    kotlin.math.sqrt(seg.sumOf { (it - m) * (it - m) } / seg.size)
                }
                else -> Double.NaN
            }
        }
    }

    /** First/last frame clearly above the noise floor. */
    fun findSpeechSpan(tracks: Tracks): Pair<Int, Int> {
        val thresh = tracks.floorDb + max(12.0, 0.30 * (tracks.peakDb - tracks.floorDb))
        val active = BooleanArray(tracks.nFrames) { tracks.rmsDb[it] > thresh }
        val closed = close(active, 5)
        val idx = closed.indices.filter { closed[it] }
        if (idx.isEmpty()) return 0 to tracks.nFrames
        return idx.first() to idx.last() + 1
    }

    /** Peaks of voiced energy as `(start, end, peak)` frame spans. */
    fun findNuclei(tracks: Tracks, minDistanceMs: Double = 100.0, minProminenceDb: Double = 3.0, maxN: Int = 8): List<Triple<Int, Int, Int>> {
        val (lo, hi) = findSpeechSpan(tracks)
        val ve = tracks.venergy.copyOf()
        for (i in 0 until lo) ve[i] = Double.NEGATIVE_INFINITY
        for (i in hi until ve.size) ve[i] = Double.NEGATIVE_INFINITY
        val distance = max(1, (minDistanceMs / 1000.0 / tracks.hopSeconds).roundToInt())
        val peaks = CheapDsp.findPeaks(ve, distance = distance, prominence = minProminenceDb)
        if (peaks.isEmpty()) {
            if (hi > lo) {
                var p = lo
                for (i in lo until hi) if (tracks.rmsDb[i] > tracks.rmsDb[p]) p = i
                return listOf(Triple(max(lo, p - 2), minOf(hi, p + 3), p))
            }
            return emptyList()
        }
        val order = peaks.indices.sortedByDescending { ve[peaks[it]] }.take(maxN)
        val nuclei = ArrayList<Triple<Int, Int, Int>>()
        for (idx in order.sorted()) {
            val p = peaks[idx]
            val level = tracks.rmsDb[p] - 12.0
            var left = p
            while (left > lo && tracks.rmsDb[left - 1] > level && tracks.voiced[left - 1]) left--
            var right = p + 1
            while (right < hi && tracks.rmsDb[right] > level && tracks.voiced[right]) right++
            nuclei.add(Triple(left, right, p))
        }
        return nuclei
    }

    class Analysis(val tracks: Tracks, val speech: Pair<Int, Int>, val nuclei: List<Triple<Int, Int, Int>>)

    fun analyze(pcm: DoubleArray, sampleRate: Int): Analysis {
        val tracks = Tracks(pcm, sampleRate)
        return Analysis(tracks, findSpeechSpan(tracks), findNuclei(tracks))
    }
}
