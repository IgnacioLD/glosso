package com.glosso.engine.phonetics.cheap

import com.glosso.engine.model.AcousticFindingModel
import com.glosso.engine.model.FluencyModel

/**
 * Reference-free accent / fluency score from prosody and rhythm.
 *
 * Port of the combined accent score in `benchmark/cheap-scorers/analyze.py`
 * (PR #43). The report measured that these cheap, model-free features separate
 * native from non-native speech with AUC 0.777, against 0.575 for the current
 * end-to-end score on the same 60 clips.
 *
 * The reference analysis z-normalises each feature against the mean/std of the
 * CV corpus it happened to see. Production has no corpus, so the anchors below
 * are fixed normative constants for English. They are derived from the report's
 * per-feature native/non-native means and its per-feature AUC (equal-variance
 * Gaussian inversion), so the feature directions and relative weights are the
 * ones the report validated. [sign] is +1 when the native production has a higher
 * value; the contribution is `sign * (value - mean) / std`, so a *higher* total
 * means *more accented*.
 */
internal object FluencyScorer {

    data class Norm(val mean: Double, val std: Double, val sign: Int)

    val ENGLISH: Map<String, Norm> = linkedMapOf(
        "speech_rate_syl_per_s" to Norm(2.62190, 0.95527, -1),
        "pause_ratio" to Norm(0.05955, 0.07272, +1),
        "longest_pause_seconds" to Norm(0.20565, 0.33455, +1),
        "f0_std_semitones" to Norm(4.30015, 1.61123, -1),
        "f0_range_semitones" to Norm(9.90005, 5.17643, -1),
        "npvi" to Norm(65.64355, 32.58372, -1),
    )

    private fun value(f: CheapFeatures.Result, key: String): Double? = when (key) {
        "speech_rate_syl_per_s" -> f.speechRateSylPerS
        "pause_ratio" -> f.pauseRatio
        "longest_pause_seconds" -> f.longestPauseSeconds
        "f0_std_semitones" -> f.f0StdSemitones
        "f0_range_semitones" -> f.f0RangeSemitones
        "npvi" -> f.npvi
        else -> null
    }

    /** Prior-signed z-score average, or null when no feature could be measured. */
    fun accentScore(features: CheapFeatures.Result, norm: Map<String, Norm> = ENGLISH): Double? {
        val parts = ArrayList<Double>()
        for ((key, n) in norm) {
            val v = value(features, key) ?: continue
            parts.add(n.sign * (v - n.mean) / n.std)
        }
        if (parts.isEmpty()) return null
        return parts.average()
    }

    private fun finding(code: String, message: String): AcousticFindingModel =
        AcousticFindingModel(code = code, phoneme = "-", message = message)

    fun report(features: CheapFeatures.Result, norm: Map<String, Norm> = ENGLISH): FluencyModel? {
        val score = accentScore(features, norm) ?: return null
        val findings = ArrayList<AcousticFindingModel>()

        val rate = features.speechRateSylPerS
        if (rate != null && rate < norm.getValue("speech_rate_syl_per_s").mean - 0.4) {
            findings.add(finding("fluency.rate.slow", "speaking rate ${fmt(rate)} syllables/s (native about 2.9)"))
        }
        val longest = features.longestPauseSeconds
        if (longest != null && longest > norm.getValue("longest_pause_seconds").mean + 0.1) {
            findings.add(finding("fluency.pause.long", "longest pause ${fmt(longest)} s (native about 0.12 s)"))
        }
        val f0Std = features.f0StdSemitones
        if (f0Std != null && f0Std < norm.getValue("f0_std_semitones").mean - 0.8) {
            findings.add(finding("fluency.intonation.flat", "pitch variation ${fmt(f0Std)} semitones (native about 5.0)"))
        }

        return FluencyModel(
            accentScore = score.toFloat(),
            speechRateSylPerS = features.speechRateSylPerS?.toFloat(),
            pauseRatio = features.pauseRatio?.toFloat(),
            longestPauseSeconds = features.longestPauseSeconds?.toFloat(),
            f0StdSemitones = features.f0StdSemitones?.toFloat(),
            f0RangeSemitones = features.f0RangeSemitones?.toFloat(),
            npvi = features.npvi?.toFloat(),
            findings = findings,
        )
    }

    private fun fmt(v: Double): String {
        val r = (v * 100).toLong() / 100.0
        return r.toString()
    }
}
