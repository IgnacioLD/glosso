package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.engine.model.LanguagePacks
import kotlin.test.Test
import kotlin.test.assertEquals

class VoicePreferencesTest {

    @Test
    fun visitingAnotherLanguageKeepsTheAccentChosenForEnglish() {
        VoicePreferences.init(MemoryStore(), LanguagePacks.ENGLISH)
        VoicePreferences.set("en-US")

        VoicePreferences.onLanguageChanged(LanguagePacks.byCode("ru"))
        assertEquals("ru-RU", VoicePreferences.accent)

        VoicePreferences.onLanguageChanged(LanguagePacks.ENGLISH)
        assertEquals("en-US", VoicePreferences.accent)
    }

    @Test
    fun anAccentSavedByAnOlderVersionIsKept() {
        val store = MemoryStore().apply { values["accent"] = "en-US" }
        VoicePreferences.init(store, LanguagePacks.ENGLISH)
        assertEquals("en-US", VoicePreferences.accent)
    }

    @Test
    fun theChoiceSurvivesARestartAfterVisitingAnotherLanguage() {
        val store = MemoryStore()
        VoicePreferences.init(store, LanguagePacks.ENGLISH)
        VoicePreferences.set("en-US")
        VoicePreferences.onLanguageChanged(LanguagePacks.byCode("ru"))

        VoicePreferences.init(store, LanguagePacks.ENGLISH)
        assertEquals("en-US", VoicePreferences.accent)
    }

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String) = values[key]
        override fun getBoolean(key: String, default: Boolean) = default
        override fun contains(key: String) = key in values
        override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
            object : KeyValueStore.Editor {
                override fun putString(key: String, value: String) { values[key] = value }
                override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
                override fun remove(key: String) { values.remove(key) }
                override fun clear() = values.clear()
            }.block()
        }
    }
}
