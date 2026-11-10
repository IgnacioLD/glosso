package com.glosso.engine.phonetics.quality

import com.glosso.engine.model.PhoneQualityModel
import com.glosso.engine.model.PronunciationQualityModel
import com.glosso.engine.phonetics.AcousticFrames
import com.glosso.engine.phonetics.PhoneSpan
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The decision layer that makes pronunciation scoring transfer across speakers
 * and channels: within-utterance normalised per-phone features plus a frozen
 * logistic head, aggregated by the mean of the worst phones.
 *
 * Port of `benchmark/phon-multilingual/finalize.py` (PR #52). The key finding it
 * encodes: absolute posteriors carry speaker/channel bias and do not separate
 * native from non-native speech (AUC 0.49, below chance), while features
 * normalised against the learner's own utterance do (honest speaker-disjoint
 * held-out AUC ~0.60). The per-phone mean dilutes the signal; the worst-3
 * phones carry it.
 *
 * This is additive: it never changes [com.glosso.engine.model.PronunciationFeedback.score]
 * or its scale. Returns null when there is nothing to judge (no phones, an
 * unmatched vocabulary, or an unrepresentable reference), so a degenerate clip
 * simply has no quality signal instead of a wrong one.
 */
object PronunciationQuality {

    const val HEAD_ID = QualityHead.ID

    /** Utterance score = mean of the N worst per-phone probabilities. */
    const val WORST_N = 3

    fun analyze(
        reference: List<String>,
        frames: AcousticFrames,
        spans: List<PhoneSpan>,
    ): PronunciationQualityModel? {
        if (reference.isEmpty() || spans.isEmpty()) return null
        // The head's token one-hot is tied to the production vocabulary; a model
        // with a different inventory cannot use it.
        if (frames.vocabSize != QualityHead.VOCAB_SIZE) return null

        val raw = QualityFeatures.extract(frames, spans)
        if (raw.isEmpty()) return null

        val gopMean = raw.map { it.gop }.average()
        val gopStd = stdOrOne(raw.map { it.gop })
        val entropyMean = raw.map { it.entropy }.average()
        val entropyStd = stdOrOne(raw.map { it.entropy })

        val phones = raw.map { f ->
            val canonical = reference.getOrNull(f.phoneIndex) ?: "?"
            val produced = frames.tokenOf(f.producedId) ?: "?"
            val phoneClass = PhonemeClass.of(canonical)
            val match = if (produced == canonical) 1.0 else 0.0
            val continuous = doubleArrayOf(
                (f.gop - gopMean) / gopStd,
                f.gopRank,
                f.margin,
                (f.entropy - entropyMean) / entropyStd,
                ln(1.0 + f.frames),
                match,
            )
            val quality = QualityHead.probability(continuous, phoneClass, f.tokenId)
            PhoneQualityModel(
                phone = canonical,
                produced = produced,
                phoneClass = phoneClass,
                gop = f.gop.toFloat(),
                gopRank = f.gopRank.toFloat(),
                margin = f.margin.toFloat(),
                entropy = f.entropy.toFloat(),
                frames = f.frames,
                quality = quality.toFloat(),
            )
        }

        val worst = phones.sortedBy { it.quality }.take(WORST_N)
        val score = worst.map { it.quality.toDouble() }.average()
        return PronunciationQualityModel(
            score = score.toFloat(),
            worst = worst,
            phones = phones,
            head = HEAD_ID,
        )
    }

    /** Population std, matching `numpy.ndarray.std`; 1.0 when flat (`x.std() or 1.0`). */
    private fun stdOrOne(values: List<Double>): Double {
        if (values.isEmpty()) return 1.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val std = sqrt(variance)
        return if (std == 0.0) 1.0 else std
    }
}
