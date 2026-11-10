package com.glosso.engine.phonetics.cheap

import com.glosso.engine.model.PhoneTimingModel
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Safety and explainability checks for the cheap scorer, beyond raw parity:
 * a silent or too-short clip must never throw, and the per-phone acoustics must
 * actually explain the contrasts the report calls out.
 */
class CheapScorerTest {

    @Test
    fun `a silent clip does not throw and yields no unusable numbers`() {
        val report = CheapScorer.analyze(DoubleArray(8_000), 16_000)
        report.features.f0Mean?.let { assertTrue(it.isFinite()) }
        report.acoustics.forEach { assertNotNull(it.phoneme) }
    }

    @Test
    fun `a clip shorter than one frame does not throw`() {
        CheapScorer.analyze(DoubleArray(100) { it / 100.0 }, 16_000)
    }

    @Test
    fun `an empty clip does not throw`() {
        CheapScorer.analyze(DoubleArray(0), 16_000)
    }

    @Test
    fun `a sibilant theta is explained with its centroid`() {
        val pcm = readPcm("cheap/fric_s.wav")
        val timings = listOf(PhoneTimingModel(phone = "θ", startMs = 0, endMs = 300, confidence = 1f))
        val acoustics = CheapScorer.analyze(pcm, 16_000, timings).acoustics
        val theta = acoustics.firstOrNull { it.phoneme == "θ" } ?: fail("no acoustics for θ")
        assertNotNull(theta.fricativeCentroidHz, "centroid must be measured")
        val finding = theta.findings.firstOrNull { it.code == "fricative.theta.sibilant" }
        assertNotNull(finding, "a high centroid must be flagged as sibilant, was ${theta.findings}")
        assertTrue(finding.message!!.contains("centroid"), "the explanation carries the number")
    }

    @Test
    fun `ih produced as iy is explained with its F2`() {
        val pcm = readPcm("cheap/vowel_iy.wav")
        val timings = listOf(PhoneTimingModel(phone = "ɪ", startMs = 0, endMs = 450, confidence = 1f))
        val acoustics = CheapScorer.analyze(pcm, 16_000, timings).acoustics
        val ih = acoustics.firstOrNull { it.phoneme == "ɪ" } ?: fail("no acoustics for ɪ")
        assertNotNull(ih.f2, "F2 must be measured")
        assertNotNull(
            ih.findings.firstOrNull { it.code == "vowel.ih.f2_high" },
            "a high F2 must be flagged as too close to /i/, was ${ih.findings}",
        )
    }

    @Test
    fun `a good ih is not flagged`() {
        val pcm = readPcm("cheap/vowel_ih.wav")
        val timings = listOf(PhoneTimingModel(phone = "ɪ", startMs = 0, endMs = 450, confidence = 1f))
        val ih = CheapScorer.analyze(pcm, 16_000, timings).acoustics.first { it.phoneme == "ɪ" }
        assertTrue(
            ih.findings.none { it.code == "vowel.ih.f2_high" },
            "a native-like ɪ must not be flagged, was ${ih.findings}",
        )
    }

    private fun readPcm(path: String): DoubleArray {
        val bytes = CheapScorerTest::class.java.getResourceAsStream("/$path")?.readAllBytes()
            ?: fail("missing test resource: $path")
        val sampleCount = (bytes.size - 44) / 2
        return DoubleArray(sampleCount) { i ->
            val b0 = bytes[44 + i * 2].toInt() and 0xff
            val b1 = bytes[44 + i * 2 + 1].toInt() and 0xff
            ((b0 or (b1 shl 8)).toShort()).toDouble() / 32768.0
        }
    }
}
