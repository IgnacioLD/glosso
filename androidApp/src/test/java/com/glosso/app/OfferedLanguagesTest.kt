package com.glosso.app

import com.glosso.engine.model.LanguagePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferedLanguagesTest {

    @Test
    fun `a build with no extra languages offers exactly what the engine offers`() {
        assertEquals(LanguagePacks.offered, OfferedLanguages.offered(""))
    }

    @Test
    fun `an extra language is offered once and only when the engine has its pack`() {
        val offered = OfferedLanguages.offered("de, en,xx")

        assertEquals(LanguagePacks.offered.map { it.code } + "de", offered.map { it.code })
        assertEquals("de", OfferedLanguages.resolve(offered, "de-DE")?.code)
        assertNull(OfferedLanguages.resolve(offered, "fr"))
        assertNull(OfferedLanguages.resolve(offered, null))
    }
}
