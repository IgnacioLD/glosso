package com.glosso.engine.phonetics.cheap

import com.glosso.engine.model.AcousticFindingModel
import com.glosso.engine.model.FluencyModel
import com.glosso.engine.model.PhoneAcousticsModel
import com.glosso.engine.model.PhoneTimingModel
import kotlin.math.roundToInt

/**
 * Cheap scorer: the reference-free accent/fluency signal plus per-phone
 * explanatory acoustics, computed from PCM alone (no model, no reference
 * prosody). This is the engine entry point the server calls to complement the
 * wav2vec2 score.
 *
 * [analyze] never changes the phoneme score or its scale: it returns a separate
 * [FluencyModel] and a per-phone [PhoneAcousticsModel] list. It is safe on
 * silence or a clip shorter than one frame.
 */
object CheapScorer {

    /** Formant anchors the report used for the /ɪ/ vs /i/ explanation. */
    private const val IH_F2_TARGET_HZ = 2400.0

    /** Centroid anchor the report used for the /θ/ vs /s/ explanation. */
    private const val THETA_CENTROID_TARGET_HZ = 4087.0

    class Report(
        val features: CheapFeatures.Result,
        val fluency: FluencyModel?,
        val acoustics: List<PhoneAcousticsModel>,
    )

    fun analyze(pcm: DoubleArray, sampleRate: Int, timings: List<PhoneTimingModel> = emptyList()): Report {
        val safePcm = if (pcm.isEmpty()) DoubleArray(1) else pcm
        val analyzed = CheapSegmenter.analyze(safePcm, sampleRate)
        val features = CheapFeatures.extract(analyzed, focus = null)
        val fluency = FluencyScorer.report(features)
        val acoustics = if (timings.isEmpty()) {
            emptyList()
        } else {
            timings.mapNotNull { acousticsFor(analyzed.tracks, it) }
        }
        return Report(features, fluency, acoustics)
    }

    private fun acousticsFor(tracks: CheapSegmenter.Tracks, timing: PhoneTimingModel): PhoneAcousticsModel? {
        val span = timingSpanFrames(tracks, timing) ?: return null
        if (span.second <= span.first) return null
        val phone = timing.phone
        val phoneClass = CheapSegmenter.phoneClass(phone)
        return when (phoneClass) {
            "vowel" -> vowelAcoustics(tracks, span, phone)
            "fric" -> fricativeAcoustics(tracks, span, phone)
            else -> null
        }
    }

    private fun timingSpanFrames(tracks: CheapSegmenter.Tracks, timing: PhoneTimingModel): Pair<Int, Int>? {
        if (tracks.sampleRate <= 0 || tracks.hop <= 0) return null
        val start = (timing.startMs / 1000.0 * tracks.sampleRate / tracks.hop).roundToInt()
        val end = (timing.endMs / 1000.0 * tracks.sampleRate / tracks.hop).roundToInt()
        val lo = start.coerceIn(0, tracks.nFrames)
        val hi = end.coerceIn(lo, tracks.nFrames)
        return lo to hi
    }

    private fun vowelAcoustics(tracks: CheapSegmenter.Tracks, span: Pair<Int, Int>, phone: String): PhoneAcousticsModel {
        val f = CheapFeatures.vowelFeatures(tracks, span)
        val f1 = f["f1"]
        val f2 = f["f2"]
        val f3 = f["f3"]
        val findings = ArrayList<AcousticFindingModel>()
        when (phone) {
            "ɪ" -> if (f2 != null && f2 > IH_F2_TARGET_HZ) {
                findings.add(
                    AcousticFindingModel(
                        code = "vowel.ih.f2_high",
                        phoneme = phone,
                        measured = f2.toFloat(),
                        target = IH_F2_TARGET_HZ.toFloat(),
                        message = "/ɪ/ F2 ${f2.roundToInt()} Hz (target <= $IH_F2_TARGET_HZ): too close to /i/",
                    ),
                )
            }
            "i" -> if (f2 != null && f2 < IH_F2_TARGET_HZ) {
                findings.add(
                    AcousticFindingModel(
                        code = "vowel.iy.f2_low",
                        phoneme = phone,
                        measured = f2.toFloat(),
                        target = IH_F2_TARGET_HZ.toFloat(),
                        message = "/i/ F2 ${f2.roundToInt()} Hz (target > $IH_F2_TARGET_HZ): too close to /ɪ/",
                    ),
                )
            }
        }
        return PhoneAcousticsModel(
            phoneme = phone,
            f1 = f1?.toFloat(),
            f2 = f2?.toFloat(),
            f3 = f3?.toFloat(),
            findings = findings,
        )
    }

    private fun fricativeAcoustics(tracks: CheapSegmenter.Tracks, span: Pair<Int, Int>, phone: String): PhoneAcousticsModel {
        val f = CheapFeatures.fricativeFeatures(tracks, span)
        val centroid = f["fric_centroid"]
        val zcr = f["fric_zcr"]
        val findings = ArrayList<AcousticFindingModel>()
        if (phone == "θ" && centroid != null && centroid > THETA_CENTROID_TARGET_HZ * 1.08) {
            findings.add(
                AcousticFindingModel(
                    code = "fricative.theta.sibilant",
                    phoneme = phone,
                    measured = centroid.toFloat(),
                    target = THETA_CENTROID_TARGET_HZ.toFloat(),
                    message = "/θ/ centroid ${centroid.roundToInt()} Hz (target ~${THETA_CENTROID_TARGET_HZ.roundToInt()}): too sibilant, closer to /s/",
                ),
            )
        }
        return PhoneAcousticsModel(
            phoneme = phone,
            fricativeCentroidHz = centroid?.toFloat(),
            fricativeZcr = zcr?.toFloat(),
            findings = findings,
        )
    }
}
