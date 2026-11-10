package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.engine.model.LanguagePack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The reference accent for the whole app, scoped to the target language: each
 * language ships its own accents (`en-US`/`en-GB`). The voice itself is not a
 * learner choice: every narrated item has one voice chosen by the content (see
 * `audio/VoiceClips.kt`) and dialogue lines play their speaker's voice. Kept out
 * of the view models so a single choice in Settings applies to free practice and
 * every lesson alike, without threading it through each screen.
 */
object VoicePreferences {

    private const val FILE = "glosso_voice"

    /** Written by older versions, when the learner picked the voice's gender; now dropped. */
    private const val LEGACY_GENDER = "gender"

    private lateinit var prefs: KeyValueStore

    private val accentState = MutableStateFlow("en-GB")

    /**
     * The accent as a flow: the content is worded per accent, so the screens that
     * hold content reload when it changes.
     */
    val accentFlow: StateFlow<String> = accentState.asStateFlow()

    var accent: String
        get() = accentState.value
        private set(value) {
            accentState.value = value
        }

    fun init(context: PlatformContext) {
        prefs = keyValueStore(context, FILE)
        val pack = LanguagePreferences.current
        accent = prefs.getString("accent") ?: pack.defaultAccent
        if (prefs.contains(LEGACY_GENDER)) prefs.edit { remove(LEGACY_GENDER) }
        normalize(pack)
    }

    fun set(accent: String) {
        this.accent = accent
        persist()
    }

    /** Re-points the reference accent when the target language changes. */
    fun onLanguageChanged(pack: LanguagePack) = normalize(pack)

    /** Keeps the accent valid for [pack], falling back to the one it ships by default. */
    private fun normalize(pack: LanguagePack) {
        if (pack.accents.none { it.code == accent }) {
            accent = pack.defaultAccent
            persist()
        }
    }

    private fun persist() {
        if (::prefs.isInitialized) {
            prefs.edit { putString("accent", accent) }
        }
    }
}
