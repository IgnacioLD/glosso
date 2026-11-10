package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A meaning is only shown for the interface language. The content carries a
 * single translation (Spanish) today, so with the interface in English, French
 * or German the line must be absent, never the Spanish text.
 */
class TranslationTest {

    private val spanishOnly = mapOf("es" to "El sendero pasa junto a la puerta norte.")

    @Test
    fun `uses the translation for the interface language`() {
        assertEquals("El sendero pasa junto a la puerta norte.", spanishOnly.translationFor("es"))
    }

    @Test
    fun `never falls back to another language`() {
        assertNull(spanishOnly.translationFor("en"))
        assertNull(spanishOnly.translationFor("fr"))
        assertNull(spanishOnly.translationFor("de"))
    }

    @Test
    fun `a blank translation counts as missing`() {
        assertNull(mapOf("es" to "  ").translationFor("es"))
    }

    @Test
    fun `picks the right one when several exist`() {
        val many = mapOf("es" to "hola", "en" to "hello", "fr" to "bonjour")
        assertEquals("hello", many.translationFor("en"))
        assertEquals("bonjour", many.translationFor("fr"))
    }
}
