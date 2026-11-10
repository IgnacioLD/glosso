package com.glosso.engine.phonetics.cheap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared, framework-free DSP primitives for the cheap pronunciation probes.
 *
 * A line-by-line port of `benchmark/cheap-scorers/cheap/dsp.py` (PR #43), which
 * measured that a pure-DSP front end reproduces some of the wav2vec2 signal at
 * ~0 MB and a few ms per clip. Everything is plain Kotlin so it runs identically
 * on the JVM and on Android without a model; the parity test pins the port to the
 * Python reference on the same signals.
 *
 * All arrays are Double mono at [sampleRate] (16 kHz in production).
 */
internal object CheapDsp {

    const val EPS = 1e-12

    // --- framing -------------------------------------------------------------

    /** Zero-pads the tail and returns an `[nFrames, win]` matrix. */
    fun frameSignal(x: DoubleArray, win: Int, hop: Int): Array<DoubleArray> {
        val xx = if (x.size < win) x.copyOf(win) else x
        val nFrames = 1 + (xx.size - win) / hop
        return Array(nFrames) { f ->
            val start = f * hop
            DoubleArray(win) { i -> xx[start + i] }
        }
    }

    /** `numpy.hanning`: symmetric, both endpoints zero. */
    fun hann(win: Int): DoubleArray = when {
        win <= 0 -> DoubleArray(0)
        win == 1 -> doubleArrayOf(1.0)
        else -> DoubleArray(win) { n -> 0.5 - 0.5 * cos(2.0 * PI * n / (win - 1)) }
    }

    fun frameRms(frames: Array<DoubleArray>): DoubleArray =
        DoubleArray(frames.size) { f ->
            var sum = 0.0
            val fr = frames[f]
            for (v in fr) sum += v * v
            sqrt(sum / fr.size + EPS)
        }

    /**
     * Fraction of sign changes per frame. Uses the IEEE sign bit so -0.0 counts
     * as negative, matching `numpy.signbit`.
     */
    fun frameZcr(frames: Array<DoubleArray>): DoubleArray =
        DoubleArray(frames.size) { f ->
            val fr = frames[f]
            var changes = 0
            for (i in 1 until fr.size) {
                if (signbit(fr[i]) != signbit(fr[i - 1])) changes++
            }
            changes.toDouble() / (fr.size - 1)
        }

    private fun signbit(v: Double): Boolean = v.toRawBits() < 0L

    // --- FFT -----------------------------------------------------------------

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vRe = re[b] * wRe - im[b] * wIm
                    val vIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - vRe
                    im[b] = im[a] - vIm
                    re[a] += vRe
                    im[a] += vIm
                    val nwRe = wRe * wr - wIm * wi
                    wIm = wRe * wi + wIm * wr
                    wRe = nwRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    /** `numpy.fft.rfft` of [x] zero-padded (or truncated) to [n] power of two. */
    fun rfft(x: DoubleArray, n: Int): Array<DoubleArray> {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in x.indices) if (i < n) re[i] = x[i]
        fft(re, im, false)
        val half = n / 2 + 1
        return Array(half) { doubleArrayOf(re[it], im[it]) }
    }

    /** `numpy.fft.irfft`: Hermitian spectrum of length n/2+1 in, n real samples out. */
    fun irfft(specRe: DoubleArray, specIm: DoubleArray, n: Int): DoubleArray {
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val half = n / 2 + 1
        for (k in 0 until min(half, specRe.size)) {
            re[k] = specRe[k]
            im[k] = specIm[k]
        }
        for (k in half until n) {
            val m = n - k
            re[k] = re[m]
            im[k] = -im[m]
        }
        fft(re, im, true)
        return re
    }

    private fun magnitude(spec: Array<DoubleArray>): DoubleArray =
        DoubleArray(spec.size) { hypot(spec[it][0], spec[it][1]) }

    // --- F0 ------------------------------------------------------------------

    private fun parabolic(logMag: DoubleArray, k: Int): Double {
        if (k <= 0 || k >= logMag.size - 1) return 0.0
        val a = logMag[k - 1]
        val b = logMag[k]
        val c = logMag[k + 1]
        val denom = a - 2.0 * b + c
        if (abs(denom) < 1e-12) return 0.0
        return 0.5 * (a - c) / denom
    }

    /** Autocorrelation F0 and periodicity clarity for one frame; 0 when unvoiced. */
    fun autocorrF0(frame: DoubleArray, sampleRate: Int, fmin: Double = 60.0, fmax: Double = 400.0): Pair<Double, Double> {
        val n = frame.size
        val x = DoubleArray(n)
        var mean = 0.0
        for (v in frame) mean += v
        mean /= n
        var peakAbs = 0.0
        for (i in 0 until n) {
            val v = frame[i] - mean
            x[i] = v
            if (abs(v) > peakAbs) peakAbs = abs(v)
        }
        if (peakAbs < EPS) return 0.0 to 0.0

        val nfft = 1 shl ceil(log2((2 * n).toDouble())).toInt()
        val spec = rfft(x, nfft)
        val powerRe = DoubleArray(spec.size) { val re = spec[it][0]; val im = spec[it][1]; re * re + im * im }
        val ac = irfft(powerRe, DoubleArray(spec.size), nfft)
        if (ac[0] < EPS) return 0.0 to 0.0

        val lo = max(1, (sampleRate / fmax).toInt())
        val hi = min(n - 2, (sampleRate / fmin).toInt())
        if (hi <= lo) return 0.0 to 0.0

        var k = 0
        var best = Double.NEGATIVE_INFINITY
        for (i in lo..hi) {
            val v = ac[i] / ac[0]
            if (v > best) {
                best = v
                k = i - lo
            }
        }
        if (best < 0.25) return 0.0 to 0.0

        val segSize = hi - lo + 1
        val logSeg = DoubleArray(segSize) { max(ac[lo + it] / ac[0], 1e-9) }.map { ln(it) }.toDoubleArray()
        val lag = lo + k + parabolic(logSeg, k)
        if (lag < 1) return 0.0 to 0.0
        return sampleRate / lag to best
    }

    class F0Track(val f0: DoubleArray, val clarity: DoubleArray, val win: Int, val hop: Int, val hopSeconds: Double)

    /** Frame-wise F0 + clarity over a whole signal. */
    fun trackF0(
        x: DoubleArray,
        sampleRate: Int,
        winMs: Double = 40.0,
        hopMs: Double = 10.0,
        fmin: Double = 60.0,
        fmax: Double = 400.0,
    ): F0Track {
        val win = (sampleRate * winMs / 1000).roundToInt()
        val hop = (sampleRate * hopMs / 1000).roundToInt()
        val frames = frameSignal(x, win, hop)
        val f0 = DoubleArray(frames.size)
        val clarity = DoubleArray(frames.size)
        for (i in frames.indices) {
            val (f, c) = autocorrF0(frames[i], sampleRate, fmin, fmax)
            f0[i] = f
            clarity[i] = c
        }
        return F0Track(f0, clarity, win, hop, hop.toDouble() / sampleRate)
    }

    // --- spectral moments and bands ------------------------------------------

    fun spectralFrames(frames: Array<DoubleArray>, sampleRate: Int, nfft: Int = 512, win: DoubleArray? = null): Array<DoubleArray> {
        val w = win ?: hann(frames.firstOrNull()?.size ?: 0)
        return Array(frames.size) { f ->
            val row = frames[f]
            val x = DoubleArray(nfft) { i -> if (i < row.size && i < w.size) row[i] * w[i] else 0.0 }
            magnitude(rfft(x, nfft))
        }
    }

    class SpectralMoments(
        val centroid: DoubleArray,
        val spread: DoubleArray,
        val skew: DoubleArray,
        val kurtosis: DoubleArray,
        val flatness: DoubleArray,
    )

    fun spectralMoments(mag: Array<DoubleArray>, sampleRate: Int, nfft: Int = 512): SpectralMoments {
        val nBins = mag.firstOrNull()?.size ?: 0
        val freqs = DoubleArray(nBins) { it * sampleRate.toDouble() / nfft }
        val frames = mag.size
        val centroid = DoubleArray(frames)
        val spread = DoubleArray(frames)
        val skew = DoubleArray(frames)
        val kurtosis = DoubleArray(frames)
        val flatness = DoubleArray(frames)
        for (f in 0 until frames) {
            val row = mag[f]
            var total = 0.0
            for (b in 0 until nBins) total += row[b] * row[b] + EPS
            var c = 0.0
            for (b in 0 until nBins) c += freqs[b] * (row[b] * row[b] + EPS) / total
            var sp = 0.0
            var sk = 0.0
            var ku = 0.0
            for (b in 0 until nBins) {
                val p = (row[b] * row[b] + EPS) / total
                val d = freqs[b] - c
                sp += d * d * p
                sk += d * d * d * p
                ku += d * d * d * d * p
            }
            sp = sqrt(sp)
            val safe = max(sp, EPS)
            var logSum = 0.0
            var arith = 0.0
            for (b in 0 until nBins) {
                val p = row[b] * row[b] + EPS
                logSum += ln(p)
                arith += p
            }
            centroid[f] = c
            spread[f] = sp
            skew[f] = sk / (safe * safe * safe)
            kurtosis[f] = ku / (safe * safe * safe * safe)
            flatness[f] = exp(logSum / nBins) / (arith / nBins + EPS)
        }
        return SpectralMoments(centroid, spread, skew, kurtosis, flatness)
    }

    /** Relative energy in the fixed Hz bands the reference uses. */
    fun bandEnergy(mag: Array<DoubleArray>, sampleRate: Int, nfft: Int = 512): Map<String, DoubleArray> {
        val nBins = mag.firstOrNull()?.size ?: 0
        val freqs = DoubleArray(nBins) { it * sampleRate.toDouble() / nfft }
        val edges = listOf(0 to 1000, 1000 to 2000, 2000 to 4000, 4000 to 8000)
        val out = LinkedHashMap<String, DoubleArray>()
        for ((lo, hi) in edges) {
            val mask = BooleanArray(nBins) { freqs[it] >= lo && freqs[it] < hi }
            val arr = DoubleArray(mag.size)
            for (f in mag.indices) {
                var total = 0.0
                var band = 0.0
                for (b in 0 until nBins) {
                    val p = mag[f][b] * mag[f][b] + EPS
                    total += p
                    if (mask[b]) band += p
                }
                arr[f] = band / total
            }
            out["band_${lo}_${hi}"] = arr
        }
        return out
    }

    // --- cepstral formants ---------------------------------------------------

    /**
     * Smooth log-spectral envelope of one frame via real-cepstrum liftering.
     *
     * LPC roots are unstable on this corpus: a high-pitched voice (F0 ~210-270 Hz)
     * shows F0 harmonics as credible but false formants. Liftering smooths the
     * harmonic comb away and keeps the vocal-tract envelope, the cheap and stable
     * trade the reference selected.
     */
    fun cepstralEnvelope(frame: DoubleArray, sampleRate: Int, nfft: Int = 1024, lifter: Int = 30): DoubleArray {
        val n = frame.size
        val win = hann(n)
        val spec = rfft(DoubleArray(n) { frame[it] * win[it] }, nfft)
        val logMag = DoubleArray(spec.size) { ln(hypot(spec[it][0], spec[it][1]) + EPS) }
        val cep = irfft(logMag, DoubleArray(spec.size), nfft)
        val keep = DoubleArray(nfft)
        for (i in 0..min(lifter, nfft - 1)) keep[i] = 1.0
        for (i in max(0, nfft - lifter) until nfft) keep[i] = 1.0
        val env = rfft(DoubleArray(nfft) { cep[it] * keep[it] }, nfft)
        return DoubleArray(env.size) { exp(env[it][0]) }
    }

    /** Estimate F1/F2/F3 from the cepstral envelope, one per anchored band. */
    fun envelopeFormants(
        frame: DoubleArray,
        sampleRate: Int,
        nfft: Int = 1024,
        lifter: Int = 30,
        maxFormants: Int = 3,
        fmin: Double = 200.0,
        fmax: Double = 4600.0,
        minSepHz: Double = 550.0,
    ): List<Double> {
        val env = cepstralEnvelope(frame, sampleRate, nfft, lifter)
        if (env.isEmpty()) return emptyList()
        val freqs = DoubleArray(env.size) { it * sampleRate.toDouble() / nfft }

        fun tallest(lo: Double, hi: Double): Double? {
            val idx = (env.indices).filter { freqs[it] >= lo && freqs[it] <= hi }
            if (idx.isEmpty()) return null
            val e = DoubleArray(idx.size) { env[idx[it]] }
            val peaks = localMaxima(e)
            return if (peaks.isEmpty()) {
                var bi = 0
                for (i in e.indices) if (e[i] > e[bi]) bi = i
                if (e[bi] > EPS) freqs[idx[bi]] else null
            } else {
                var bestP = peaks[0]
                for (p in peaks) if (e[p] > e[bestP]) bestP = p
                freqs[idx[bestP]]
            }
        }

        val formants = ArrayList<Double>(maxFormants)
        val f1 = tallest(fmin, min(1000.0, fmax)) ?: return emptyList()
        formants.add(f1)
        if (maxFormants >= 2) {
            val f2 = tallest(f1 + minSepHz, min(3400.0, fmax))
            if (f2 != null) {
                formants.add(f2)
                if (maxFormants >= 3) {
                    val f3 = tallest(f2 + minSepHz, fmax)
                    if (f3 != null) formants.add(f3)
                }
            }
        }
        return formants.take(maxFormants)
    }

    // --- peak finding (faithful port of scipy.signal.find_peaks) --------------

    /** Local maxima; a flat run of equal values yields the middle index. */
    fun localMaxima(x: DoubleArray): IntArray {
        val peaks = ArrayList<Int>()
        val n = x.size
        var i = 1
        while (i < n - 1) {
            if (x[i] > x[i - 1]) {
                var j = i
                while (j + 1 < n && x[j + 1] == x[i]) j++
                if (x[i - 1] < x[i] && j + 1 < n && x[j + 1] < x[i]) peaks.add((i + j) / 2)
                i = j + 1
            } else {
                i++
            }
        }
        return peaks.toIntArray()
    }

    fun peakProminences(x: DoubleArray, peaks: IntArray): DoubleArray {
        val out = DoubleArray(peaks.size)
        for (pi in peaks.indices) {
            val p = peaks[pi]
            var minLeft = x[p]
            var i = p - 1
            while (i >= 0) {
                if (x[i] > x[p]) break
                if (x[i] < minLeft) minLeft = x[i]
                i--
            }
            var minRight = x[p]
            i = p + 1
            while (i < x.size) {
                if (x[i] > x[p]) break
                if (x[i] < minRight) minRight = x[i]
                i++
            }
            out[pi] = x[p] - max(minLeft, minRight)
        }
        return out
    }

    private fun selectByDistance(peaks: IntArray, priority: DoubleArray, distance: Int): IntArray {
        val order = peaks.indices.sortedByDescending { priority[it] }
        val kept = ArrayList<Int>()
        for (idx in order) {
            val p = peaks[idx]
            if (kept.all { abs(p - it) >= distance }) kept.add(p)
        }
        return kept.sorted().toIntArray()
    }

    fun findPeaks(
        x: DoubleArray,
        distance: Int? = null,
        prominence: Double? = null,
    ): IntArray {
        var peaks = localMaxima(x)
        if (distance != null) peaks = selectByDistance(peaks, DoubleArray(peaks.size) { x[peaks[it]] }, distance)
        if (prominence != null) {
            val pr = peakProminences(x, peaks)
            peaks = peaks.filterIndexed { i, _ -> pr[i] >= prominence }.toIntArray()
        }
        return peaks
    }

    // --- small stats ---------------------------------------------------------

    /** `numpy.percentile` with the default linear interpolation. */
    fun percentile(values: DoubleArray, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val rank = q / 100.0 * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        if (lo == hi) return sorted[lo]
        return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo])
    }

    /** `numpy.interp` over an ascending [xp]. */
    fun interp(x: DoubleArray, xp: DoubleArray, fp: DoubleArray): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in x.indices) {
            val xi = x[i]
            if (xi <= xp[0]) {
                out[i] = fp[0]
                continue
            }
            if (xi >= xp[xp.size - 1]) {
                out[i] = fp[fp.size - 1]
                continue
            }
            var lo = 0
            var hi = xp.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (xp[mid] <= xi) lo = mid else hi = mid
            }
            val frac = if (xp[hi] == xp[lo]) 0.0 else (xi - xp[lo]) / (xp[hi] - xp[lo])
            out[i] = fp[lo] + frac * (fp[hi] - fp[lo])
        }
        return out
    }

    /** `numpy.linspace(start, stop, num)`, including the exact endpoints. */
    fun linspace(start: Double, stop: Double, num: Int): DoubleArray {
        if (num <= 0) return DoubleArray(0)
        if (num == 1) return doubleArrayOf(start)
        val step = (stop - start) / (num - 1)
        return DoubleArray(num) { if (it == num - 1) stop else start + step * it }
    }
}
