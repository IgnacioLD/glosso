package com.glosso.engine.phonetics

import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.ProsodyBonusModel
import com.glosso.engine.phonetics.cheap.CheapFeatures
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Extra points for rhythm and intonation ("Ritmo y entonacion"), measured
 * against the exercise's own reference audio.
 *
 * Additive and separate by contract: [evaluate] never reads or changes the sound
 * score, it returns its own [ProsodyBonusModel] with 0..[MAX_POINTS] points. The
 * points are shown next to the score, never folded into it, so the base score,
 * the native >=98 metric and the separation AUC are the same with the flag on
 * or off. They can only add: a take with no measurable shape gets 0 (or null,
 * no section), never a penalty.
 *
 * The shape is [ProsodyShapeComparator] (rhythm, tempo, stress, intonation) plus
 * a pause dimension from [CheapFeatures] on both sides. A shape at or below
 * [SHAPE_FLOOR] earns nothing; above it the points grow linearly and reach the
 * cap [MAX_POINTS] at [SHAPE_FULL]. See `docs/PROSODY-EXTRA.md` for what each
 * dimension measures, its limits and the probe numbers behind the constants.
 *
 * Off by default ([ENABLED_BY_DEFAULT]): it needs the reference audio aligned on
 * the scoring side (a second model pass), and it is only switched on after the
 * probe on real learner audio.
 */
object ProsodyBonus {

    const val ENABLED_BY_DEFAULT = false

    /** Hard cap on the extra points. */
    const val MAX_POINTS = 5

    /**
     * Shape (0..1) at or below which no points are given. Above every synthetic
     * word-by-word reading of the probe (max 0.589) and below every native
     * reading of the same text in another voice (min 0.744).
     */
    const val SHAPE_FLOOR = 0.6

    /**
     * Shape from which the full [MAX_POINTS] are given. Two different native
     * voices never match exactly (probe median 0.816, p75 0.883), so the cap is
     * reachable without being a copy of the reference audio.
     */
    const val SHAPE_FULL = 0.9

    /**
     * Below this completeness too many reference sounds were not produced for
     * their timing to describe the learner's rhythm; no section is shown.
     */
    const val MIN_COMPLETENESS = 60

    /** Fewer segments than this have no rhythm or contour to compare. */
    const val MIN_SEGMENTS = 3

    /** This much pause time beyond the reference's takes the pause dimension to 0. */
    const val PAUSE_ZERO_MS = 1500.0

    /** One side of the comparison: its aligned segments and total pause time. */
    class Take(val segments: List<ProsodyShapeComparator.Segment>, val pauseMs: Int?)

    /**
     * Builds a [Take] from the aligner's per-phone timings and the PCM they were
     * measured on (the silence-trimmed audio of `AudioAnalysis.pcm`). Without PCM
     * the pause dimension is dropped.
     */
    fun take(timings: List<PhoneTimingModel>, pcm: FloatArray?, sampleRate: Int): Take {
        val pauseMs = if (pcm == null || pcm.isEmpty() || sampleRate <= 0) {
            null
        } else {
            val features = CheapFeatures.extract(DoubleArray(pcm.size) { pcm[it].toDouble() }, sampleRate, focus = null)
            val ratio = features.pauseRatio
            val speech = features.speechSeconds
            if (ratio == null || speech == null) null else (ratio * speech * 1000).roundToInt()
        }
        return Take(ProsodyShapeComparator.fromTimings(timings), pauseMs)
    }

    /**
     * The bonus for [learner] against [reference], or null when it does not
     * apply (different alignments, too few segments, too incomplete a take).
     */
    fun evaluate(reference: Take, learner: Take, completeness: Int): ProsodyBonusModel? {
        if (completeness < MIN_COMPLETENESS) return null
        if (reference.segments.size < MIN_SEGMENTS) return null
        val components = ProsodyShapeComparator.compare(reference.segments, learner.segments) ?: return null
        val extraPauseMs = if (reference.pauseMs != null && learner.pauseMs != null) {
            max(0, learner.pauseMs - reference.pauseMs)
        } else {
            null
        }
        val pauses = extraPauseMs?.let { (1.0 - it / PAUSE_ZERO_MS).coerceIn(0.0, 1.0) }
        val shape = ProsodyShapeComparator.combine(components, pauses)
        return ProsodyBonusModel(
            points = points(shape),
            maxPoints = MAX_POINTS,
            shape = shape.toFloat(),
            rhythm = components.rhythm.toFloat(),
            tempo = components.tempo.toFloat(),
            pauses = pauses?.toFloat(),
            stress = components.stress.toFloat(),
            intonation = components.intonation?.toFloat(),
            tempoRatio = components.tempoRatio.toFloat(),
            extraPauseMs = extraPauseMs,
        )
    }

    /** Points for a shape: 0 up to [SHAPE_FLOOR], linear to [MAX_POINTS] at [SHAPE_FULL]. */
    fun points(shape: Double): Int {
        val fraction = ((shape - SHAPE_FLOOR) / (SHAPE_FULL - SHAPE_FLOOR)).coerceIn(0.0, 1.0)
        return (fraction * MAX_POINTS).roundToInt().coerceIn(0, MAX_POINTS)
    }
}
