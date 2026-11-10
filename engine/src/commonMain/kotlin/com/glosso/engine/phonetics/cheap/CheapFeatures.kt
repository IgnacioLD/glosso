package com.glosso.engine.phonetics.cheap

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Interpreterable cheap acoustic features, port of the ported subset of
 * `benchmark/cheap-scorers/cheap/features.py` (PR #43).
 *
 * Families ported: vowels (cepstral formants), fricatives (spectral moments) and
 * prosody/rhythm (reference-free). Stops (VOT/closure/burst) and attempt
 * consistency are deliberately absent: the report measured them at chance
 * (AUC 0.571) and confounded respectively.
 */
object CheapFeatures {

    data class Result(
        val f1: Double?, val f2: Double?, val f3: Double?, val f2MinusF1: Double?,
        val f1Std: Double?, val f2Std: Double?,
        val fricCentroid: Double?, val fricSpread: Double?, val fricSkew: Double?,
        val fricKurtosis: Double?, val fricFlatness: Double?, val fricHighRatio: Double?,
        val fricZcr: Double?, val fricDuration: Double?,
        val f0Mean: Double?, val f0Median: Double?, val f0StdSemitones: Double?,
        val f0RangeSemitones: Double?, val f0SlopeSemitonesPerS: Double?,
        val f0FinalDeltaSemitones: Double?, val voicedRatio: Double?,
        val speechSeconds: Double?, val nSyllables: Int, val speechRateSylPerS: Double?,
        val articulationRateSylPerS: Double?, val meanNucleusSeconds: Double?,
        val stdNucleusSeconds: Double?, val npvi: Double?,
        val pauseCount: Int, val pauseRatio: Double?, val longestPauseSeconds: Double?,
        val meanPauseSeconds: Double?,
        val focusClass: String, val nucleusSeconds: Double?, val onsetSeconds: Double?, val codaSeconds: Double?,
    )

    private fun safe(v: Double): Double? = if (v.isFinite()) v else null

    private fun std(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        return CheapDsp.percentile(values.toDoubleArray(), 50.0)
    }

    private fun centerSpan(lo: Int, hi: Int, frac: Double): Pair<Int, Int> {
        val n = max(0, hi - lo)
        if (n == 0) return lo to hi
        val keep = max(1, (n * frac).roundToInt())
        val mid = (lo + hi) / 2
        val half = keep / 2
        return max(lo, mid - half) to min(hi, mid + half + (keep % 2))
    }

    // --- family 1: vowels ----------------------------------------------------

    internal fun vowelFeatures(tracks: CheapSegmenter.Tracks, span: Pair<Int, Int>): Map<String, Double?> {
        val (lo, hi) = centerSpan(span.first, span.second, 0.6)
        val out = HashMap<String, Double?>()
        out["f1"] = null; out["f2"] = null; out["f3"] = null
        out["f2_minus_f1"] = null; out["f1_std"] = null; out["f2_std"] = null
        if (hi <= lo) return out
        val f1s = ArrayList<Double>()
        val f2s = ArrayList<Double>()
        val f3s = ArrayList<Double>()
        for (f in lo until hi) {
            val fs = CheapDsp.envelopeFormants(tracks.frames[f], tracks.sampleRate, maxFormants = 3)
            if (fs.size >= 3) {
                f1s.add(fs[0]); f2s.add(fs[1]); f3s.add(fs[2])
            } else if (fs.size == 2) {
                f1s.add(fs[0]); f2s.add(fs[1])
            }
        }
        if (f1s.isNotEmpty()) {
            out["f1"] = safe(median(f1s))
            out["f2"] = if (f2s.isNotEmpty()) safe(median(f2s)) else null
            out["f3"] = if (f3s.isNotEmpty()) safe(median(f3s)) else null
            if (f2s.isNotEmpty()) {
                out["f2_minus_f1"] = (out["f2"] ?: Double.NaN) - (out["f1"] ?: Double.NaN)
                out["f1_std"] = safe(std(f1s))
                out["f2_std"] = safe(std(f2s))
            }
        }
        return out
    }

    // --- family 3: fricatives ------------------------------------------------

    internal fun fricativeFeatures(tracks: CheapSegmenter.Tracks, span: Pair<Int, Int>): Map<String, Double?> {
        val (lo, hi) = span
        val out = HashMap<String, Double?>()
        out["fric_centroid"] = null; out["fric_spread"] = null; out["fric_skew"] = null
        out["fric_kurtosis"] = null; out["fric_flatness"] = null; out["fric_high_ratio"] = null
        out["fric_zcr"] = null; out["fric_duration"] = null
        if (hi <= lo) return out
        out["fric_centroid"] = safe(tracks.sliceStat(tracks.centroid, lo, hi, "mean"))
        out["fric_spread"] = safe(tracks.sliceStat(tracks.spread, lo, hi, "mean"))
        out["fric_skew"] = safe(tracks.sliceStat(tracks.skew, lo, hi, "mean"))
        out["fric_kurtosis"] = safe(tracks.sliceStat(tracks.kurtosis, lo, hi, "mean"))
        out["fric_flatness"] = safe(tracks.sliceStat(tracks.flatness, lo, hi, "mean"))
        out["fric_high_ratio"] = safe(tracks.sliceStat(tracks.highRatio, lo, hi, "mean"))
        out["fric_zcr"] = safe(tracks.sliceStat(tracks.zcr, lo, hi, "mean"))
        out["fric_duration"] = tracks.spanSeconds(lo, hi)
        return out
    }

    // --- family 4: prosody ---------------------------------------------------

    private fun prosodyFeatures(tracks: CheapSegmenter.Tracks, speech: Pair<Int, Int>): Map<String, Double?> {
        val (lo, hi) = speech
        val out = HashMap<String, Double?>()
        out["f0_mean"] = null; out["f0_median"] = null; out["f0_std_semitones"] = null
        out["f0_range_semitones"] = null; out["f0_slope_semitones_per_s"] = null
        out["f0_final_delta_semitones"] = null; out["voiced_ratio"] = null
        if (hi <= lo) return out

        val f0 = ArrayList<Double>()
        val t = ArrayList<Double>()
        var voicedCount = 0
        for (i in lo until hi) {
            if (tracks.voiced[i] && tracks.f0[i] > 0) {
                f0.add(tracks.f0[i])
                t.add((i - lo) * tracks.hopSeconds)
            }
            if (tracks.voiced[i]) voicedCount++
        }
        if (f0.isEmpty()) return out

        val f0Median = median(f0)
        val st = DoubleArray(f0.size) { 12.0 * log2(f0[it] / f0Median + CheapDsp.EPS) }
        out["f0_mean"] = safe(f0.average())
        out["f0_median"] = safe(f0Median)
        out["f0_std_semitones"] = safe(std(st.toList()))
        out["f0_range_semitones"] = safe(CheapDsp.percentile(st, 90.0) - CheapDsp.percentile(st, 10.0))
        out["voiced_ratio"] = safe(voicedCount.toDouble() / (hi - lo))

        val tArr = t.toDoubleArray()
        if (tArr.size >= 4) {
            val ptp = tArr.max() - tArr.min()
            if (ptp > CheapDsp.EPS) {
                val tm = tArr.average()
                val sm = st.average()
                var cov = 0.0
                var varT = 0.0
                for (i in tArr.indices) {
                    cov += (tArr[i] - tm) * (st[i] - sm)
                    varT += (tArr[i] - tm) * (tArr[i] - tm)
                }
                out["f0_slope_semitones_per_s"] = safe(if (varT == 0.0) Double.NaN else cov / varT)
            }
        }

        val cutA = (0.75 * st.size).toInt()
        if (cutA >= 2 && st.size - cutA >= 1) {
            var last = 0.0
            for (i in cutA until st.size) last += st[i]
            last /= (st.size - cutA)
            var first = 0.0
            for (i in 0 until cutA) first += st[i]
            first /= cutA
            out["f0_final_delta_semitones"] = safe(last - first)
        }
        return out
    }

    // --- family 5: rhythm / fluency ------------------------------------------

    private fun rhythmFeatures(
        tracks: CheapSegmenter.Tracks,
        speech: Pair<Int, Int>,
        nuclei: List<Triple<Int, Int, Int>>,
    ): Map<String, Double?> {
        val (lo, hi) = speech
        val speechSeconds = max(tracks.spanSeconds(lo, hi), CheapDsp.EPS)
        val out = HashMap<String, Double?>()
        out["speech_seconds"] = speechSeconds
        out["n_syllables"] = nuclei.size.toDouble()
        out["speech_rate_syl_per_s"] = null
        out["articulation_rate_syl_per_s"] = null
        out["mean_nucleus_seconds"] = null
        out["std_nucleus_seconds"] = null
        out["npvi"] = null
        out["pause_count"] = 0.0
        out["pause_ratio"] = 0.0
        out["longest_pause_seconds"] = 0.0
        out["mean_pause_seconds"] = null

        if (nuclei.size >= 1) {
            out["speech_rate_syl_per_s"] = safe(nuclei.size.toDouble() / speechSeconds)
            val durs = nuclei.map { tracks.spanSeconds(it.first, it.second) }
            val voicedSeconds = durs.sum()
            out["mean_nucleus_seconds"] = safe(durs.average())
            out["std_nucleus_seconds"] = safe(std(durs))
            out["articulation_rate_syl_per_s"] = safe(nuclei.size.toDouble() / max(voicedSeconds, CheapDsp.EPS))
            if (durs.size >= 2) {
                var acc = 0.0
                for (i in 0 until durs.size - 1) {
                    acc += kotlin.math.abs(durs[i + 1] - durs[i]) / ((durs[i] + durs[i + 1]) / 2.0 + CheapDsp.EPS)
                }
                out["npvi"] = safe(100.0 * acc / (durs.size - 1))
            }
        }

        val thresh = tracks.floorDb + 6.0
        val minFrames = max(1, (0.12 / tracks.hopSeconds).roundToInt())
        val runs = ArrayList<Int>()
        var run = 0
        for (i in lo until hi) {
            if (tracks.rmsDb[i] < thresh) {
                run++
            } else {
                if (run >= minFrames) runs.add(run)
                run = 0
            }
        }
        if (run >= minFrames) runs.add(run)
        out["pause_count"] = runs.size.toDouble()
        if (runs.isNotEmpty()) {
            val pauseFrames = runs.sum()
            out["pause_ratio"] = safe(pauseFrames.toDouble() / max(hi - lo, 1))
            out["longest_pause_seconds"] = safe(runs.max().toDouble() * tracks.hopSeconds)
            out["mean_pause_seconds"] = safe(runs.average() * tracks.hopSeconds)
        }
        return out
    }

    private fun pickNucleus(nuclei: List<Triple<Int, Int, Int>>): Triple<Int, Int, Int> {
        if (nuclei.isEmpty()) return Triple(0, 0, 0)
        var best = nuclei[0]
        for (n in nuclei) if (n.second - n.first > best.second - best.first) best = n
        return best
    }

    fun extract(pcm: DoubleArray, sampleRate: Int, focus: String?): Result =
        extract(CheapSegmenter.analyze(pcm, sampleRate), focus)

    internal fun extract(analyzed: CheapSegmenter.Analysis, focus: String?): Result {
        val tracks = analyzed.tracks
        val speech = analyzed.speech
        val nuclei = analyzed.nuclei
        val (nStart, nEnd, _) = pickNucleus(nuclei)
        val onset = speech.first to nStart
        val coda = nEnd to speech.second

        val feat = HashMap<String, Double?>()
        feat.putAll(vowelFeatures(tracks, nStart to nEnd))
        feat.putAll(fricativeFeatures(tracks, onset))
        feat.putAll(prosodyFeatures(tracks, speech))
        feat.putAll(rhythmFeatures(tracks, speech, nuclei))

        return Result(
            f1 = feat["f1"], f2 = feat["f2"], f3 = feat["f3"], f2MinusF1 = feat["f2_minus_f1"],
            f1Std = feat["f1_std"], f2Std = feat["f2_std"],
            fricCentroid = feat["fric_centroid"], fricSpread = feat["fric_spread"], fricSkew = feat["fric_skew"],
            fricKurtosis = feat["fric_kurtosis"], fricFlatness = feat["fric_flatness"],
            fricHighRatio = feat["fric_high_ratio"], fricZcr = feat["fric_zcr"], fricDuration = feat["fric_duration"],
            f0Mean = feat["f0_mean"], f0Median = feat["f0_median"], f0StdSemitones = feat["f0_std_semitones"],
            f0RangeSemitones = feat["f0_range_semitones"], f0SlopeSemitonesPerS = feat["f0_slope_semitones_per_s"],
            f0FinalDeltaSemitones = feat["f0_final_delta_semitones"], voicedRatio = feat["voiced_ratio"],
            speechSeconds = feat["speech_seconds"], nSyllables = (feat["n_syllables"] ?: 0.0).toInt(),
            speechRateSylPerS = feat["speech_rate_syl_per_s"], articulationRateSylPerS = feat["articulation_rate_syl_per_s"],
            meanNucleusSeconds = feat["mean_nucleus_seconds"], stdNucleusSeconds = feat["std_nucleus_seconds"],
            npvi = feat["npvi"], pauseCount = (feat["pause_count"] ?: 0.0).toInt(),
            pauseRatio = feat["pause_ratio"], longestPauseSeconds = feat["longest_pause_seconds"],
            meanPauseSeconds = feat["mean_pause_seconds"],
            focusClass = if (focus != null) CheapSegmenter.phoneClass(focus) else "other",
            nucleusSeconds = tracks.spanSeconds(nStart, nEnd),
            onsetSeconds = tracks.spanSeconds(onset.first, onset.second),
            codaSeconds = tracks.spanSeconds(coda.first, coda.second),
        )
    }
}
