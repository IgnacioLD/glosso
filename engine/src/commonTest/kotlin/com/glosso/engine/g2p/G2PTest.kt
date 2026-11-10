package com.glosso.engine.g2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArpabetTest {

    @Test
    fun `primary stress becomes a leading stress mark`() {
        assertEquals("ˈæ", Arpabet.phoneme("AE1"))
    }

    @Test
    fun `secondary stress becomes a secondary mark`() {
        assertEquals("ˌæ", Arpabet.phoneme("AE2"))
    }

    @Test
    fun `unstressed AH is schwa and unstressed ER is rhotacized schwa`() {
        assertEquals("ə", Arpabet.phoneme("AH0"))
        assertEquals("ɚ", Arpabet.phoneme("ER0"))
    }

    @Test
    fun `stressed AH and ER keep their full vowels`() {
        assertEquals("ˈʌ", Arpabet.phoneme("AH1"))
        assertEquals("ˈɝ", Arpabet.phoneme("ER1"))
    }

    @Test
    fun `unknown tokens return null`() {
        assertNull(Arpabet.phoneme("ZZZ"))
    }

    @Test
    fun `a whole word converts phone by phone`() {
        assertEquals("kˈæt", Arpabet.word("K AE1 T"))
    }
}

class CmuDictG2PTest {

    @Test
    fun `phonemizes a known word`() {
        val g2p = CmuDictG2P(mapOf("cat" to listOf("K AE1 T")))
        assertEquals("kˈæt", g2p.phonemize("cat"))
    }

    @Test
    fun `keeps one token per word and punctuation is stripped`() {
        val g2p = CmuDictG2P(
            mapOf("cat" to listOf("K AE1 T"), "dog" to listOf("D AO1 G")),
        )
        assertEquals("kˈæt dˈɔɡ", g2p.phonemize("cat, dog!"))
    }

    @Test
    fun `unknown words pass through unchanged`() {
        val g2p = CmuDictG2P(mapOf("cat" to listOf("K AE1 T")))
        assertEquals("kˈæt zorblax", g2p.phonemize("cat zorblax"))
    }

    @Test
    fun `fromLines parses cmudict rows and alternate pronunciations`() {
        val g2p = CmuDictG2P.fromLines(
            listOf(
                ";;; comment",
                "CAT  K AE1 T",
                "DOG(2)  D AO1 G",
            ),
        )
        assertEquals("kˈæt", g2p.phonemize("cat"))
        assertEquals("dˈɔɡ", g2p.phonemize("dog"))
    }

    @Test
    fun `keeps every alternate reading of a word as a variant`() {
        val g2p = CmuDictG2P.fromLines(
            listOf(
                "THIRTY  TH ER1 D IY2",
                "THIRTY(2)  TH ER1 T IY2",
            ),
        )
        val pronunciations = g2p.pronunciations("thirty")
        assertEquals("θˈɝdˌi", pronunciations.canonical)
        assertEquals(listOf(listOf("θˈɝdˌi", "θˈɝtˌi")), pronunciations.wordVariants)
    }

    @Test
    fun `wordVariants stay parallel to the source words when only one has variants`() {
        val g2p = CmuDictG2P.fromLines(
            listOf(
                "THE  DH AH0",
                "THIRTY  TH ER1 D IY2",
                "THIRTY(2)  TH ER1 T IY2",
            ),
        )
        val pronunciations = g2p.pronunciations("the thirty")
        assertEquals(2, pronunciations.wordVariants.size)
        assertEquals(listOf("ðə"), pronunciations.wordVariants[0])
        assertEquals(listOf("θˈɝdˌi", "θˈɝtˌi"), pronunciations.wordVariants[1])
    }

    @Test
    fun `a word with no alternates gets a single variant equal to its reading`() {
        val g2p = CmuDictG2P(mapOf("cat" to listOf("K AE1 T")))
        val pronunciations = g2p.pronunciations("cat")
        assertEquals(listOf(listOf("kˈæt")), pronunciations.wordVariants)
    }
}
