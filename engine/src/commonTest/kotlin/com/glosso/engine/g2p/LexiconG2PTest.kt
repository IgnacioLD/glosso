package com.glosso.engine.g2p

import kotlin.test.Test
import kotlin.test.assertEquals

class LexiconG2PTest {

    private val g2p = LexiconG2P.fromLines(
        listOf(
            ";;; test lexicon",
            "bonjour bɔ̃ʒuʁ",
            "je ʒə",
            "m'appelle mapɛl",
            "peut-être pøtɛtʁ",
        ),
    )

    @Test
    fun `phonemizes known words - one token per word`() {
        assertEquals("bɔ̃ʒuʁ ʒə mapɛl", g2p.phonemize("Bonjour, je m'appelle"))
    }

    @Test
    fun `keeps internal hyphens and apostrophes`() {
        assertEquals("pøtɛtʁ", g2p.phonemize("peut-être"))
    }

    @Test
    fun `unknown words pass through unchanged`() {
        assertEquals("zorblax blah", g2p.phonemize("zorblax blah"))
    }

    @Test
    fun `phonemizes german with umlauts - eszett and capitalised nouns`() {
        val german = LexiconG2P.fromLines(
            listOf("fünf fˈʏnf", "brüder bɾˈyːdɜ", "heißen hˈaɪsən"),
        )
        assertEquals("fˈʏnf bɾˈyːdɜ hˈaɪsən", german.phonemize("Fünf Brüder heißen..."))
        assertEquals("bɾˈyːdɜ", german.phonemize("BRÜDER!"))
    }

    @Test
    fun `phonemizes russian in cyrillic - one token per word`() {
        val russian = LexiconG2P.fromLines(
            listOf("мать mˈɑtʲ", "хочет xˈotʃit", "читать tʃitˈɑtʲ", "ёлка jˈɵɭka"),
        )
        assertEquals("mˈɑtʲ xˈotʃit tʃitˈɑtʲ", russian.phonemize("Мать хочет читать."))
        assertEquals("jˈɵɭka", russian.phonemize("«Ёлка»"))
        assertEquals("мат", russian.phonemize("Мат"))
    }

    @Test
    fun `empty ipa entries are ignored`() {
        val parsed = LexiconG2P.fromLines(listOf("word  "))
        assertEquals("word", parsed.phonemize("word"))
    }

    @Test
    fun `an authored stress mark does not hide the word from the lexicon`() {
        val g2p = LexiconG2P.fromLines(listOf("город ɡˈorʌt", "она ʌnˈɑ", "в v"))

        assertEquals("ɡˈorʌt", g2p.phonemize("Го\u0301род."))
        assertEquals("ʌnˈɑ v ɡˈorʌt", g2p.phonemize("Она\u0301 в го\u0301род?"))
        assertEquals("ʌnˈɑ ɡˈorʌt", g2p.phonemize("Она\u0301 - го\u0301род."))
    }
}
