package com.glosso.engine.phonetics

import com.glosso.engine.model.PhoneTimingModel
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shape similarity between a learner's prosody and the exercise's reference
 * audio, segment by segment: relative rhythm, overall tempo, the energy
 * envelope (stress) and the pitch contour (intonation). It is measured *against
 * the reference* the learner is imitating, so a take only scores high when its
 * rhythm, stress and intonation follow the example.
 *
 * Pure DSP over the per-phone [PhoneTimingModel]s the forced aligner already
 * produces for both the learner and the reference (see
 * `Wav2Vec2PhonemeRecognizer.align` and [ProsodyAnalyzer]); no model and no
 * platform calls, so the JVM and Android compute the same number.
 *
 * The two sides must share the same segmentation (same reference phones in the
 * same order). [compare] and [similarity] return null otherwise, so a caller
 * never combines numbers from two different alignments.
 */
object ProsodyShapeComparator {

    /** One segment's measured prosody, in reference-phone order. */
    data class Segment(val durationMs: Int, val energy: Float, val f0Hz: Float?)

    /**
     * Each dimension on 0..1, 1 = the same shape as the reference.
     *
     * @property rhythm relative duration pattern, after removing the overall tempo.
     * @property tempo overall speaking-time ratio, tolerant of a comfortably
     *   slower or faster reading ([TEMPO_FREE_RATIO]).
     * @property stress correlation of the energy envelopes.
     * @property intonation pitch contour relative to each side's own median, or
     *   null when either side has no usable pitch.
     * @property tempoRatio learner speaking time / reference speaking time.
     */
    data class Components(
        val rhythm: Double,
        val tempo: Double,
        val stress: Double,
        val intonation: Double?,
        val tempoRatio: Double,
    )

    // Rhythm dominates among the timing dimensions: it is what a learner can most
    // consciously fix. Tempo is tolerant and light, a slow careful reading is not
    // a prosody error. Stress and intonation split the melody. [PAUSES_WEIGHT] is
    // applied by the caller that measured pauses (see [ProsodyBonus]).
    const val RHYTHM_WEIGHT = 0.25
    const val TEMPO_WEIGHT = 0.10
    const val PAUSES_WEIGHT = 0.15
    const val STRESS_WEIGHT = 0.25
    const val INTONATION_WEIGHT = 0.25

    /** A tempo within this factor of the reference (either way) is fully fine. */
    const val TEMPO_FREE_RATIO = 1.25

    /** At this factor (twice as slow or as fast) the tempo dimension reaches 0. */
    const val TEMPO_ZERO_RATIO = 2.0

    /**
     * One acoustic frame (20 ms) of slack on every duration: the aligner cannot
     * place a boundary finer than that, so a 20 vs 40 ms plosive is not a 2x
     * rhythm error.
     */
    private const val FRAME_TOLERANCE_MS = 20.0

    /** 6 semitones of relative deviation is a large intonation miss. */
    private const val MAX_SEMITONE_DELTA = 6.0

    /** Deviations past this are read as a pitch-tracker octave error. */
    private const val OCTAVE_FOLD_ABOVE = 9.0

    fun fromTimings(timings: List<PhoneTimingModel>): List<Segment> =
        timings.map { Segment(durationMs = (it.endMs - it.startMs).coerceAtLeast(0), energy = it.energy, f0Hz = it.f0Hz) }

    /** Per-dimension similarity, or null when the two sides cannot be compared. */
    fun compare(reference: List<Segment>, learner: List<Segment>): Components? {
        if (reference.isEmpty() || learner.size != reference.size) return null
        val refTotal = reference.sumOf { it.durationMs }
        val learnerTotal = learner.sumOf { it.durationMs }
        if (refTotal <= 0 || learnerTotal <= 0) return null
        val tempoRatio = learnerTotal.toDouble() / refTotal
        return Components(
            rhythm = rhythmScore(reference, learner, tempoRatio),
            tempo = tempoScore(tempoRatio),
            stress = envelopeScore(reference, learner),
            intonation = pitchScore(reference, learner),
            tempoRatio = tempoRatio,
        )
    }

    /**
     * Overall shape similarity, 0..1: 1 when the learner follows the reference
     * exactly. [pauses] is folded in when the caller measured it. Dimensions that
     * could not be measured (no pitch, no pause profile) are dropped, not zeroed,
     * and the weights renormalise over what was measured.
     */
    fun combine(components: Components, pauses: Double? = null): Double {
        var sum = RHYTHM_WEIGHT * components.rhythm + TEMPO_WEIGHT * components.tempo +
            STRESS_WEIGHT * components.stress
        var weight = RHYTHM_WEIGHT + TEMPO_WEIGHT + STRESS_WEIGHT
        components.intonation?.let {
            sum += INTONATION_WEIGHT * it
            weight += INTONATION_WEIGHT
        }
        pauses?.let {
            sum += PAUSES_WEIGHT * it
            weight += PAUSES_WEIGHT
        }
        return (sum / weight).coerceIn(0.0, 1.0)
    }

    /** [combine] over [compare], without pauses; null when not comparable. */
    fun similarity(reference: List<Segment>, learner: List<Segment>): Double? =
        compare(reference, learner)?.let { combine(it) }

    /**
     * Mean per-segment duration ratio after scaling the learner to the reference
     * speaking time: a take read uniformly slower keeps a perfect rhythm, one
     * that drags some sounds and rushes others does not.
     */
    private fun rhythmScore(reference: List<Segment>, learner: List<Segment>, tempoRatio: Double): Double {
        var sum = 0.0
        for (i in reference.indices) {
            val a = reference[i].durationMs + FRAME_TOLERANCE_MS
            val b = learner[i].durationMs / tempoRatio + FRAME_TOLERANCE_MS
            sum += min(a, b) / max(a, b)
        }
        return sum / reference.size
    }

    private fun tempoScore(ratio: Double): Double {
        val factor = max(ratio, 1.0 / ratio)
        if (factor <= TEMPO_FREE_RATIO) return 1.0
        return (1.0 - (factor - TEMPO_FREE_RATIO) / (TEMPO_ZERO_RATIO - TEMPO_FREE_RATIO)).coerceIn(0.0, 1.0)
    }

    /**
     * Pearson correlation of the two energy sequences (overall loudness is
     * normalised out, so only the stress pattern counts), mapped from [-1,1] to
     * [0,1]. A constant sequence on either side counts as 1: there is no pattern
     * to follow or to miss.
     */
    private fun envelopeScore(reference: List<Segment>, learner: List<Segment>): Double {
        val r = zscores(reference.map { it.energy.toDouble() })
        val l = zscores(learner.map { it.energy.toDouble() })
        if (r == null || l == null) return 1.0
        var dot = 0.0
        for (i in r.indices) dot += r[i] * l[i]
        val correlation = dot / r.size
        return ((correlation + 1.0) / 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * Pitch contour similarity in semitones relative to each side's own median
     * voiced pitch, so a learner with a higher or lower voice is not penalised -
     * only the shape of the intonation is compared. Each contour is median-
     * smoothed over neighbouring voiced segments first, and a deviation near an
     * octave is folded back: per-phone autocorrelation pitch jitters and halves
     * or doubles on short or creaky spans, and that is the tracker, not the
     * speaker. Null when no segment is voiced on both sides.
     */
    private fun pitchScore(reference: List<Segment>, learner: List<Segment>): Double? {
        val ref = relativeContour(reference) ?: return null
        val lrn = relativeContour(learner) ?: return null

        var count = 0
        var sum = 0.0
        for (i in reference.indices) {
            val rd = ref[i] ?: continue
            val ld = lrn[i] ?: continue
            sum += (1.0 - foldOctave(rd - ld) / MAX_SEMITONE_DELTA).coerceIn(0.0, 1.0)
            count++
        }
        return if (count == 0) null else sum / count
    }

    /** Semitones from the median, smoothed over each voiced segment's voiced neighbours. */
    private fun relativeContour(segments: List<Segment>): List<Double?>? {
        val median = medianPitch(segments) ?: return null
        val raw = segments.map { s -> s.f0Hz?.let { semitones(it, median) } }
        val voiced = raw.indices.filter { raw[it] != null }
        val smoothed = arrayOfNulls<Double>(raw.size)
        for ((k, i) in voiced.withIndex()) {
            val window = (maxOf(0, k - 1)..minOf(voiced.size - 1, k + 1)).map { raw[voiced[it]]!! }.sorted()
            smoothed[i] = window[window.size / 2]
        }
        return smoothed.toList()
    }

    /** |delta| with a near-octave jump (a tracker halving or doubling) folded back. */
    private fun foldOctave(delta: Double): Double {
        val d = abs(delta)
        return if (d > OCTAVE_FOLD_ABOVE) abs(d - 12.0) else d
    }

    private fun semitones(hz: Float, reference: Float): Double =
        if (hz <= 0f || reference <= 0f) 0.0 else 12.0 * (ln(hz / reference) / ln(2.0))

    private fun medianPitch(segments: List<Segment>): Float? {
        val voiced = segments.mapNotNull { it.f0Hz }.sorted()
        return if (voiced.isEmpty()) null else voiced[voiced.size / 2]
    }

    /** Z-scored copy, or null when the input is constant (no shape to correlate). */
    private fun zscores(values: List<Double>): DoubleArray? {
        val mean = values.average()
        var variance = 0.0
        for (v in values) variance += (v - mean) * (v - mean)
        variance /= values.size
        val std = sqrt(variance)
        if (std < 1e-9) return null
        return DoubleArray(values.size) { (values[it] - mean) / std }
    }
}
