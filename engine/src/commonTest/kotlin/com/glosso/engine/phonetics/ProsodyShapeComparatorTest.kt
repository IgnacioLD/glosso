package com.glosso.engine.phonetics

import com.glosso.engine.phonetics.ProsodyShapeComparator.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProsodyShapeComparatorTest {

    private fun seg(duration: Int, energy: Float, f0: Float?) = Segment(duration, energy, f0)

    private val reference = listOf(
        seg(120, 0.30f, 120f),
        seg(80, 0.10f, 110f),
        seg(200, 0.40f, 160f),
        seg(60, 0.12f, 130f),
    )

    @Test
    fun `identical shapes score one`() {
        assertEquals(1.0, ProsodyShapeComparator.similarity(reference, reference)!!, 1e-9)
    }

    @Test
    fun `different segment counts are not comparable`() {
        assertNull(ProsodyShapeComparator.similarity(reference, reference.dropLast(1)))
        assertNull(ProsodyShapeComparator.similarity(emptyList(), reference))
    }

    @Test
    fun `a uniformly slower reading keeps the rhythm and pays only within the tempo band`() {
        val slower = reference.map { it.copy(durationMs = (it.durationMs * 1.2).toInt()) }
        val c = ProsodyShapeComparator.compare(reference, slower)!!
        assertEquals(1.0, c.rhythm, 1e-9)
        assertEquals(1.0, c.tempo, 1e-9)
        assertEquals(1.2, c.tempoRatio, 1e-9)
    }

    @Test
    fun `twice as slow zeroes the tempo but not the rhythm`() {
        val twice = reference.map { it.copy(durationMs = it.durationMs * 2) }
        val c = ProsodyShapeComparator.compare(reference, twice)!!
        assertEquals(0.0, c.tempo, 1e-9)
        assertEquals(1.0, c.rhythm, 1e-9)
        val score = ProsodyShapeComparator.similarity(reference, twice)!!
        assertTrue(score < 1.0 && score > 0.5, "only the tempo changed: $score")
    }

    @Test
    fun `dragging some sounds and rushing others lowers the rhythm`() {
        val uneven = listOf(
            seg(40, 0.30f, 120f),
            seg(200, 0.10f, 110f),
            seg(80, 0.40f, 160f),
            seg(140, 0.12f, 130f),
        )
        val c = ProsodyShapeComparator.compare(reference, uneven)!!
        assertEquals(1.0, c.tempo, 1e-9, "same total time")
        assertTrue(c.rhythm < 0.7, "uneven rhythm: ${c.rhythm}")
    }

    @Test
    fun `one frame of boundary jitter barely moves the rhythm`() {
        val jitter = listOf(seg(140, 0.30f, 120f), seg(60, 0.10f, 110f), seg(200, 0.40f, 160f), seg(60, 0.12f, 130f))
        assertTrue(ProsodyShapeComparator.compare(reference, jitter)!!.rhythm > 0.9)
    }

    @Test
    fun `a different stress pattern lowers the envelope shape`() {
        val stressedWrong = listOf(
            seg(120, 0.10f, 120f),
            seg(80, 0.40f, 110f),
            seg(200, 0.12f, 160f),
            seg(60, 0.30f, 130f),
        )
        val score = ProsodyShapeComparator.similarity(reference, stressedWrong)!!
        assertTrue(score < 1.0, "swapped stress should not be perfect: $score")
    }

    @Test
    fun `a flat intonation against a varied reference lowers the pitch shape`() {
        val flat = reference.map { it.copy(f0Hz = 130f) }
        val score = ProsodyShapeComparator.similarity(reference, flat)!!
        assertTrue(score < 1.0, "flat intonation should not be perfect: $score")
    }

    @Test
    fun `pitch is compared by shape - not absolute voice height`() {
        // Same contour one octave up: a different voice, the same intonation.
        val octaveUp = reference.map { it.copy(f0Hz = it.f0Hz!! * 2f) }
        assertEquals(1.0, ProsodyShapeComparator.similarity(reference, octaveUp)!!, 1e-6)
    }

    @Test
    fun `unvoiced takes compare on rhythm and stress only and still return a score`() {
        val unvoiced = reference.map { it.copy(f0Hz = null) }
        val score = ProsodyShapeComparator.similarity(unvoiced, unvoiced)
        assertNotNull(score)
        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun `pauses fold in with their weight and a missing dimension is dropped - not zeroed`() {
        val c = ProsodyShapeComparator.compare(reference, reference)!!
        assertEquals(1.0, ProsodyShapeComparator.combine(c, pauses = 1.0), 1e-9)
        val withBadPauses = ProsodyShapeComparator.combine(c, pauses = 0.0)
        assertEquals(1.0 - ProsodyShapeComparator.PAUSES_WEIGHT, withBadPauses, 1e-9)
    }
}
