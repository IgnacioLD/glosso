package com.glosso.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.engine.model.LanguagePack
import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.phonetics.AccentSymbols
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A sound as the learner's accent writes it; see [AccentSymbols]. */
@Composable
internal fun shownSound(phone: String): String {
    val accent by VoicePreferences.accentFlow.collectAsState()
    return AccentSymbols.shown(phone, accent)
}

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

    /** The default language's accent, and what older versions wrote for every language. */
    private const val ACCENT = "accent"

    private lateinit var prefs: KeyValueStore

    private var languageCode = ""

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
        init(keyValueStore(context, FILE), LanguagePreferences.current)
    }

    internal fun init(store: KeyValueStore, pack: LanguagePack) {
        prefs = store
        if (prefs.contains(LEGACY_GENDER)) prefs.edit { remove(LEGACY_GENDER) }
        onLanguageChanged(pack)
    }

    fun set(accent: String) {
        this.accent = accent
        if (::prefs.isInitialized) prefs.edit { putString(keyFor(languageCode), accent) }
    }

    /** Each language keeps its own accent, so visiting another one never changes it. */
    fun onLanguageChanged(pack: LanguagePack) {
        languageCode = pack.code
        accent = if (::prefs.isInitialized) {
            listOfNotNull(prefs.getString(keyFor(pack.code)), prefs.getString(ACCENT))
                .firstOrNull { saved -> pack.accents.any { it.code == saved } } ?: pack.defaultAccent
        } else {
            pack.accents.firstOrNull { it.code == accent }?.code ?: pack.defaultAccent
        }
    }

    private fun keyFor(code: String) = if (code == LanguagePacks.default.code) ACCENT else "${ACCENT}_$code"
}
