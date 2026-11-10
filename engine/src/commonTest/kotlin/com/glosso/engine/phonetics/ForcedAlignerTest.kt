package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForcedAlignerTest {

    /** Acoustic frames with an explicit probability table, for deterministic tests. */
    private class FakeFrames(
        override val timeSteps: Int,
        override val blankId: Int,
        private val probabilities: Array<FloatArray>,
        private val tokens: Map<String, Int>,
    ) : AcousticFrames {
        override fun tokenIndex(token: String): Int = tokens[token] ?: -1
        override fun probability(frame: Int, tokenId: Int): Float = probabilities[frame][tokenId]
    }

    private val tokens = mapOf("a" to 1, "b" to 2, "c" to 3)

    /** Three phones, two frames each, in order: a a b b c c. */
    private fun threePhoneFrames(): FakeFrames {
        val active = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 3, 5 to 3)
        val probabilities = Array(6) { frame ->
            FloatArray(4) { id ->
                when (id) {
                    active[frame] -> 0.85f
                    0 -> 0.1f
                    else -> 0.025f
                }
            }
        }
        return FakeFrames(6, blankId = 0, probabilities = probabilities, tokens = tokens)
    }

    @Test
    fun `aligns each reference phone to its frames`() {
        val spans = ForcedAligner.align(listOf("a", "b", "c"), threePhoneFrames())
        assertNotNull(spans)
        assertEquals(listOf("a", "b", "c"), spans.map { it.phone })
        assertEquals(listOf(0, 2, 4), spans.map { it.startFrame })
        assertEquals(listOf(2, 4, 6), spans.map { it.endFrame })
        assertTrue(spans.all { it.posterior > 0.5f }, "spans should carry their phone's posterior")
    }

    @Test
    fun `empty reference cannot be aligned`() {
        assertNull(ForcedAligner.align(emptyList(), threePhoneFrames()))
    }

    @Test
    fun `a reference phone outside the vocabulary fails cleanly`() {
        assertNull(ForcedAligner.align(listOf("a", "z"), threePhoneFrames()))
    }

    @Test
    fun `fewer frames than phones cannot be aligned`() {
        val frames = threePhoneFrames()
        assertNull(ForcedAligner.align(listOf("a", "b", "c", "a", "b", "c", "a"), frames))
    }

    @Test
    fun `repeated phones are separated by a blank frame`() {
        // a a a with a blank between the two a phones, as CTC requires.
        val active = listOf(1, 1, 0, 0, 1, 1)
        val probabilities = Array(6) { frame ->
            FloatArray(4) { id ->
                when (id) {
                    active[frame] -> 0.85f
                    0 -> 0.1f
                    else -> 0.025f
                }
            }
        }
        val frames = FakeFrames(6, blankId = 0, probabilities = probabilities, tokens = tokens)

        val spans = ForcedAligner.align(listOf("a", "a"), frames)
        assertNotNull(spans)
        assertEquals(2, spans.size)
        // The blank run between the two a's is split at its midpoint so each a
        // gets its share of the transition, not just one 20 ms frame.
        assertEquals(0, spans[0].startFrame)
        assertEquals(3, spans[0].endFrame)
        assertEquals(3, spans[1].startFrame)
        assertEquals(6, spans[1].endFrame)
    }
}
