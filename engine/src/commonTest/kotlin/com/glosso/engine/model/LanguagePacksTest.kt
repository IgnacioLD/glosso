package com.glosso.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagePacksTest {

    @Test
    fun `region codes resolve to their base pack`() {
        assertEquals("en", LanguagePacks.byCodeOrNull("en_GB")?.code)
        assertEquals("en", LanguagePacks.byCodeOrNull("en-US")?.code)
        assertEquals("fr", LanguagePacks.byCodeOrNull("fr-FR")?.code)
        assertEquals("fr", LanguagePacks.byCodeOrNull("FR")?.code)
        assertEquals("de", LanguagePacks.byCodeOrNull("de-DE")?.code)
        assertEquals("de", LanguagePacks.byCodeOrNull("de_AT")?.code)
        assertEquals("ru", LanguagePacks.byCodeOrNull("ru-RU")?.code)
        assertEquals("ru", LanguagePacks.byCodeOrNull("RU")?.code)
    }

    @Test
    fun `the wired packs are english - french - german and russian`() {
        assertEquals(listOf("en", "fr", "de", "ru"), LanguagePacks.all.map { it.code })
        assertEquals(listOf("en", "ru"), LanguagePacks.offered.map { it.code })
    }

    @Test
    fun `unknown languages are not silently claimed`() {
        assertNull(LanguagePacks.byCodeOrNull("xx"))
        assertNull(LanguagePacks.byCodeOrNull("klingon"))
    }

    @Test
    fun `a missing code falls back to english`() {
        assertEquals("en", LanguagePacks.byCodeOrNull(null)?.code)
        assertEquals("en", LanguagePacks.byCodeOrNull("")?.code)
        assertEquals("en", LanguagePacks.forScoring(null).code)
        assertEquals("en", LanguagePacks.forScoring("klingon").code)
    }

    @Test
    fun `english defaults to the shipped british voice and scoring tables`() {
        val en = LanguagePacks.ENGLISH
        assertEquals("en-GB", en.defaultAccent)
        assertEquals("en", en.similarityKey)
        assertEquals("en", en.errorRulesKey)
        assertEquals(G2PKind.CMU_DICT, en.g2pKind)
        assertTrue(en.voices.isNotEmpty())
        // Only the accents whose audio ships are offered, each with a voice, so
        // Settings can never offer one with nothing to play.
        assertEquals(listOf("en-GB", "en-US"), en.accents.map { it.code })
        assertTrue(en.accents.all { accent -> en.voicesFor(accent.code).isNotEmpty() })
        assertTrue(en.voices.all { voice -> en.accents.any { it.code == voice.accent } })
    }

    @Test
    fun `french ships its cartesia voices and lexicon G2P`() {
        val fr = LanguagePacks.FRENCH
        assertEquals("fr-FR", fr.defaultAccent)
        assertEquals(listOf("Inès", "Léonie"), fr.voicesFor("fr-FR", VoiceGender.FEMALE).map { it.name })
        assertEquals(listOf("Étienne", "Mathieu"), fr.voicesFor("fr-FR", VoiceGender.MALE).map { it.name })
        assertEquals(G2PKind.LEXICON, fr.g2pKind)
        assertEquals("fr.lexicon", fr.g2pAsset)
        assertEquals("fr", fr.errorRulesKey)
    }

    @Test
    fun `german is wired with its lexicon - tables and voices`() {
        val de = LanguagePacks.GERMAN
        assertTrue(de.experimental)
        assertEquals(G2PKind.LEXICON, de.g2pKind)
        assertEquals("de.lexicon", de.g2pAsset)
        assertEquals("de", de.phonemizerLocale)
        assertEquals("de", de.similarityKey)
        assertEquals("de", de.errorRulesKey)
        assertEquals("de-DE", de.defaultAccent)
        assertEquals(listOf("de-DE"), de.accents.map { it.code })
        assertEquals(listOf("Lena", "Lorelei"), de.voicesFor("de-DE", VoiceGender.FEMALE).map { it.name })
        assertEquals(listOf("Clemens", "Jan"), de.voicesFor("de-DE", VoiceGender.MALE).map { it.name })
        // The front rounded vowels, the ICH/ACH sounds and both lengths of a vowel.
        assertTrue(de.phonemeInventory.containsAll(listOf("ʏ", "yː", "œ", "øː", "ç", "x", "ɪ", "iː", "pf", "ts")))
    }

    @Test
    fun `russian is wired with its lexicon - tables and voices`() {
        val ru = LanguagePacks.RUSSIAN
        assertTrue(ru.experimental)
        assertEquals(G2PKind.LEXICON, ru.g2pKind)
        assertEquals("ru.lexicon", ru.g2pAsset)
        assertEquals("ru", ru.phonemizerLocale)
        assertEquals("ru", ru.similarityKey)
        assertEquals("ru", ru.errorRulesKey)
        assertEquals("ru-RU", ru.defaultAccent)
        assertEquals(listOf("ru-RU"), ru.accents.map { it.code })
        assertEquals(listOf("Tatiana", "Irina"), ru.voicesFor("ru-RU", VoiceGender.FEMALE).map { it.name })
        assertEquals(listOf("Sergei", "Dmitri"), ru.voicesFor("ru-RU", VoiceGender.MALE).map { it.name })
        // Soft consonants, the reduced vowel and espeak's /y/ for Ы.
        assertTrue(ru.phonemeInventory.containsAll(listOf("tʲ", "nʲ", "ɭʲ", "ʑ", "ɕ", "ʌ", "y")))
    }

    @Test
    fun `every lexicon pack keeps the accent-gender voice key`() {
        for (pack in listOf(LanguagePacks.FRENCH, LanguagePacks.GERMAN, LanguagePacks.RUSSIAN)) {
            val accent = pack.defaultAccent
            assertEquals("$accent-female", pack.voiceKeyOrDefault(accent, VoiceGender.FEMALE))
            assertEquals("$accent-male", pack.voiceKeyOrDefault(accent, VoiceGender.MALE))
            assertTrue(pack.voices.all { it.language == pack.code && it.accent == accent })
            assertEquals(pack.voices.size, pack.voices.map { it.id }.toSet().size, "${pack.code}: duplicate voice id")
        }
    }

    @Test
    fun `french stays resolvable but is not offered until its content ships`() {
        assertTrue(LanguagePacks.all.any { it.code == "fr" })
        assertTrue(LanguagePacks.offered.none { it.code == "fr" })
        assertNull(LanguagePacks.offeredOrNull("fr"))
        assertNull(LanguagePacks.offeredOrNull("fr-FR"))
        assertEquals("en", LanguagePacks.offeredOrNull("en-US")?.code)
        assertEquals("fr", LanguagePacks.byCodeOrNull("fr-FR")?.code)
    }

    @Test
    fun `german stays resolvable but is not offered until its content ships`() {
        assertTrue(LanguagePacks.all.any { it.code == "de" })
        assertTrue(LanguagePacks.offered.none { it.code == "de" })
        assertNull(LanguagePacks.offeredOrNull("de"))
        assertEquals("de", LanguagePacks.forScoring("de").code)
    }

    @Test
    fun `russian is offered`() {
        assertEquals("ru", LanguagePacks.offeredOrNull("ru-RU")?.code)
        assertEquals("ru", LanguagePacks.forScoring("ru").code)
    }
}
