package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.test.assertEquals

class AccentSymbolsTest {

    @Test
    fun `a British learner reads the sounds as a British textbook writes them`() {
        assertEquals("ɒ, ɑː", AccentSymbols.shown("ɑ", "en-GB"))
        assertEquals("əʊ", AccentSymbols.shown("oʊ", "en-GB"))
        assertEquals("r", AccentSymbols.shown("ɹ", "en-GB"))
        assertEquals("θ", AccentSymbols.shown("θ", "en-GB"))
    }

    @Test
    fun `other accents keep the dictionary symbol`() {
        assertEquals("ɑ", AccentSymbols.shown("ɑ", "en-US"))
        assertEquals("ɹ", AccentSymbols.shown("ɹ", "ru-RU"))
    }
}
