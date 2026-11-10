package com.glosso.server.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VoicePaletteTest {

    private val palette = VoicePalette(defaultVoice = "default", dialogueVoices = listOf("a", "b"))

    @Test
    fun `single-voice content uses the default`() {
        assertEquals("default", palette.resolve(null))
    }

    @Test
    fun `dialogue speakers get different voices`() {
        assertEquals("a", palette.resolve(0))
        assertEquals("b", palette.resolve(1))
        assertNotEquals(palette.resolve(0), palette.resolve(1))
    }

    @Test
    fun `speakers beyond the palette cycle deterministically`() {
        assertEquals("a", palette.resolve(2))
        assertEquals("b", palette.resolve(3))
    }

    @Test
    fun `no dialogue voices falls back to the default`() {
        assertEquals("default", VoicePalette("default", emptyList()).resolve(1))
    }

    @Test
    fun `an empty palette resolves to blank so the provider default applies`() {
        assertEquals("", VoicePalette.EMPTY.resolve(0))
    }
}
