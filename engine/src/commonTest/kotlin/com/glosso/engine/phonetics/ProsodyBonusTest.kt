package com.glosso.engine.phonetics

import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.phonetics.ProsodyShapeComparator.Segment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProsodyBonusTest {

    private val reference = ProsodyBonus.Take(
        segments = listOf(
            Segment(120, 0.30f, 120f),
            Segment(80, 0.10f, 110f),
            Segment(200, 0.40f, 160f),
            Segment(60, 0.12f, 130f),
            Segment(150, 0.25f, 100f),
        ),
        pauseMs = 100,
    )

    @Test
    fun `off by default`() {
        assertFalse(ProsodyBonus.ENABLED_BY_DEFAULT)
    }

    @Test
    fun `a perfect imitation earns the capped maximum and no more`() {
        val bonus = ProsodyBonus.evaluate(reference, reference, completeness = 100)!!
        assertEquals(ProsodyBonus.MAX_POINTS, bonus.points)
        assertEquals(ProsodyBonus.MAX_POINTS, bonus.maxPoints)
        assertEquals(1f, bonus.shape, 1e-6f)
        assertEquals(0, bonus.extraPauseMs)
    }

    @Test
    fun `points are zero up to the floor - monotone above it and capped`() {
        assertEquals(0, ProsodyBonus.points(0.0))
        assertEquals(0, ProsodyBonus.points(ProsodyBonus.SHAPE_FLOOR))
        var last = 0
        var shape = 0.0
        while (shape <= 1.0) {
            val p = ProsodyBonus.points(shape)
            assertTrue(p >= last, "points must not drop as the shape improves ($shape)")
            assertTrue(p in 0..ProsodyBonus.MAX_POINTS)
            last = p
            shape += 0.01
        }
        assertEquals(ProsodyBonus.MAX_POINTS, ProsodyBonus.points(ProsodyBonus.SHAPE_FULL))
        assertEquals(ProsodyBonus.MAX_POINTS, ProsodyBonus.points(1.0))
        assertEquals(ProsodyBonus.MAX_POINTS, ProsodyBonus.points(7.0))
        assertEquals(0, ProsodyBonus.points(-3.0))
    }

    @Test
    fun `long extra pauses lower the pause dimension and the points`() {
        val halting = ProsodyBonus.Take(reference.segments, pauseMs = 100 + 1500)
        val bonus = ProsodyBonus.evaluate(reference, halting, completeness = 100)!!
        assertEquals(1500, bonus.extraPauseMs)
        assertEquals(0f, bonus.pauses!!, 1e-6f)
        assertTrue(bonus.points < ProsodyBonus.MAX_POINTS)
    }

    @Test
    fun `pausing less than the reference is not a pause error`() {
        val fluent = ProsodyBonus.Take(reference.segments, pauseMs = 0)
        assertEquals(1f, ProsodyBonus.evaluate(reference, fluent, 100)!!.pauses!!, 1e-6f)
    }

    @Test
    fun `without pcm the pause dimension is dropped - not zeroed`() {
        val noPauses = ProsodyBonus.Take(reference.segments, pauseMs = null)
        val bonus = ProsodyBonus.evaluate(reference, noPauses, 100)!!
        assertNull(bonus.pauses)
        assertEquals(ProsodyBonus.MAX_POINTS, bonus.points)
    }

    @Test
    fun `does not apply to incomplete takes - tiny items or different alignments`() {
        assertNull(ProsodyBonus.evaluate(reference, reference, ProsodyBonus.MIN_COMPLETENESS - 1))
        val two = ProsodyBonus.Take(reference.segments.take(2), 0)
        assertNull(ProsodyBonus.evaluate(two, two, 100))
        val shorter = ProsodyBonus.Take(reference.segments.dropLast(1), 100)
        assertNull(ProsodyBonus.evaluate(reference, shorter, 100))
    }

    @Test
    fun `take measures pauses from the pcm`() {
        val sr = 16_000
        // A voiced tone with a syllable-rate (4 Hz) envelope: speech-like loudness
        // swings, so the pause floor is set by real silence, not the tone level.
        fun tone(ms: Int) = FloatArray(sr * ms / 1000) {
            val syllable = 0.2 + 0.8 * abs(sin(2 * PI * 4 * it / sr))
            (0.3 * syllable * sin(2 * PI * 150 * it / sr)).toFloat()
        }
        val continuous = tone(1200)
        val gapped = tone(600) + FloatArray(sr * 600 / 1000) + tone(600)
        val timings = listOf(PhoneTimingModel("a", 0, 400, 1f), PhoneTimingModel("b", 400, 800, 1f), PhoneTimingModel("c", 800, 1200, 1f))
        val a = ProsodyBonus.take(timings, continuous, sr)
        val b = ProsodyBonus.take(timings, gapped, sr)
        assertEquals(3, a.segments.size)
        assertNotNull(a.pauseMs)
        assertNotNull(b.pauseMs)
        assertTrue(b.pauseMs >= a.pauseMs + 400, "the 600 ms gap must read as pause: ${a.pauseMs} vs ${b.pauseMs}")
        assertNull(ProsodyBonus.take(timings, null, sr).pauseMs)
    }

    @Test
    fun `the bonus rides next to the score and never changes it`() {
        val text = "I think so"
        val ipa = "ˈaɪ θˈɪŋk sˈoʊ"
        for (actual in listOf("aɪθɪŋksoʊ", "aɪsɪŋksoʊ", "sɪŋk")) {
            val result = PhoneticComparator.calculateScoringResult(text, ipa, actual)
            val without = FeedbackAssembler.assemble(text, ipa, result)
            val bonus = ProsodyBonus.evaluate(reference, reference, 100)!!
            val with = FeedbackAssembler.assemble(text, ipa, result, prosody = bonus)
            assertNull(without.prosody)
            assertEquals(bonus, with.prosody)
            assertEquals(without.copy(prosody = bonus), with, "only the prosody field may differ")
        }
    }
}
