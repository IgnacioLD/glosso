package com.glosso.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class CelebrationRingTest {

    @Test
    fun noRingIsLeftWhenTheBurstCompletes() {
        assertTrue(celebrationRings(1f).isEmpty())
        assertTrue(celebrationRings(1.5f).isEmpty())
    }

    @Test
    fun nothingIsPaintedBeforeTheBurstStarts() {
        assertTrue(celebrationRings(0f).isEmpty())
        assertTrue(celebrationRings(-0.5f).isEmpty())
    }

    @Test
    fun theSecondRingIsStaggeredBehindTheFirst() {
        val early = celebrationRings(0.1f)
        assertEquals(1, early.size)
        assertEquals(0, celebrationRings(0f).size)

        val later = celebrationRings(0.5f)
        assertEquals(2, later.size)
        assertTrue(later[0].radiusFraction > later[1].radiusFraction)
        assertTrue(later[0].alpha < later[1].alpha)
    }

    @Test
    fun aRingNeverGrowsPastTheBurst() {
        (0..20).forEach { step ->
            celebrationRings(step / 20f).forEach { ring ->
                assertTrue(ring.radiusFraction in 0f..1f)
                assertTrue(ring.alpha in 0f..0.4f)
            }
        }
    }

    @Test
    fun ringsFadeAsTheyGrow() {
        val atHalf = celebrationRings(0.5f).first()
        val atStart = celebrationRings(0.05f).first()
        assertTrue(atHalf.radiusFraction > atStart.radiusFraction)
        assertTrue(atHalf.alpha < atStart.alpha)
    }
}
