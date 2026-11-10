package com.glosso.engine.phonetics.quality

import com.glosso.engine.phonetics.AcousticFrames
import com.glosso.engine.phonetics.PhoneSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PronunciationQualityTest {

    private class Frames(
        override val timeSteps: Int,
        override val blankId: Int,
        override val vocabSize: Int,
        private val active: IntArray,
    ) : AcousticFrames {
        override fun tokenIndex(token: String): Int = -1
        override fun probability(frame: Int, tokenId: Int): Float = when (tokenId) {
            active[frame] -> 0.8f
            blankId -> 0.1f
            else -> 0.1f / (vocabSize - 2)
        }
        override fun tokenOf(id: Int): String? = if (id == active.first()) "ð" else "?"
    }

    private fun onePhoneFrames(): Frames = Frames(
        timeSteps = 4,
        blankId = 0,
        vocabSize = QualityHead.VOCAB_SIZE,
        active = intArrayOf(22, 22, 22, 22),
    )

    @Test
    fun `a single phone still scores - with rank zero and worst of one`() {
        val frames = onePhoneFrames()
        val spans = listOf(PhoneSpan("ð", 22, 0, 4, 0.8f))
        val result = assertNotNull(PronunciationQuality.analyze(listOf("ð"), frames, spans))
        assertEquals(1, result.phones.size)
        assertEquals(0.0f, result.phones[0].gopRank)
        assertEquals(1, result.worst.size)
        assertEquals(result.phones[0].quality, result.score)
        assertEquals(PronunciationQuality.HEAD_ID, result.head)
    }

    @Test
    fun `an empty reference or empty spans yields no signal`() {
        val frames = onePhoneFrames()
        assertNull(PronunciationQuality.analyze(emptyList(), frames, listOf(PhoneSpan("ð", 22, 0, 4, 0.8f))))
        assertNull(PronunciationQuality.analyze(listOf("ð"), frames, emptyList()))
    }

    @Test
    fun `a model vocabulary the head was not trained on is refused - not guessed`() {
        val small = Frames(4, 0, 10, intArrayOf(3, 3, 3, 3))
        assertNull(PronunciationQuality.analyze(listOf("ð"), small, listOf(PhoneSpan("ð", 3, 0, 4, 0.8f))))
    }

    @Test
    fun `a collapsed span is skipped like the python reference`() {
        val frames = onePhoneFrames()
        val spans = listOf(
            PhoneSpan("ð", 22, 2, 2, 0f),
            PhoneSpan("ð", 22, 0, 4, 0.8f),
        )
        val result = assertNotNull(PronunciationQuality.analyze(listOf("ð", "ð"), frames, spans))
        assertEquals(1, result.phones.size, "the zero-length span must be dropped")
    }

    @Test
    fun `the articulatory classes match the python sets`() {
        assertEquals("vowel", PhonemeClass.of("ɪ"))
        assertEquals("vowel", PhonemeClass.of("aɪ"))
        assertEquals("fricative", PhonemeClass.of("ð"))
        assertEquals("plosive", PhonemeClass.of("t"))
        assertEquals("affricate", PhonemeClass.of("tʃ"))
        assertEquals("nasal", PhonemeClass.of("ŋ"))
        assertEquals("approximant", PhonemeClass.of("ɹ"))
        assertEquals("tap", PhonemeClass.of("ɾ"))
        assertEquals("other", PhonemeClass.of("?"))
        assertEquals(PhonemeClass.NAMES, listOf(
            "vowel", "plosive", "fricative", "affricate",
            "nasal", "approximant", "tap", "other",
        ))
    }

    @Test
    fun `degenerate frames never throw and never invent a signal`() {
        val frames = Frames(1, 0, QualityHead.VOCAB_SIZE, intArrayOf(22))
        val result = PronunciationQuality.analyze(listOf("ð"), frames, listOf(PhoneSpan("ð", 22, 0, 1, 0.5f)))
        assertNotNull(result)
        assertTrue(result.score in 0f..1f)
    }
}
