package com.glosso.app.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class IpaDisplayTest {

    @Test
    fun aStressMarkInsideAWordIsGluedToTheLetterBefore() {
        assertEquals("n⁠ˈovyj", GlossoType.ipa("nˈovyj"))
        assertEquals("ˌɪn⁠ˈvaɪt", GlossoType.ipa("ˌɪnˈvaɪt"))
        assertEquals("/⁠ˈa/", GlossoType.ipa("/ˈa/"))
    }

    @Test
    fun aStressMarkStartingAWordStaysBreakable() {
        assertEquals("ˈeto n⁠ˈovyj dom", GlossoType.ipa("ˈeto nˈovyj dom"))
    }

    @Test
    fun anAlreadyShownTranscriptionIsUnchanged() {
        val shown = GlossoType.ipa("nˈovyj ˌa")
        assertEquals(shown, GlossoType.ipa(shown))
    }

    @Test
    fun textWithoutStressMarksIsUnchanged() {
        assertEquals("θriː", GlossoType.ipa("θriː"))
        assertEquals("", GlossoType.ipa(""))
    }
}
