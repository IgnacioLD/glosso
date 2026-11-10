package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the written pronunciation (IPA) is shown to the learner: the
 * transcription under a phrase or word and the phoneme symbols in the results
 * and the phonetic profile. On by default, because the pronunciation is what
 * Glosso is for (directriz 24: "Pronuncia" + frase + IPA + [Escuchar] [Hablar]);
 * the learner who finds it noisy turns it off in Settings. Persisted and shared
 * by every screen through one flow, so flipping it takes effect at once without
 * restarting the app. It only hides what is displayed: scoring keeps using the
 * same phonetics.
 */
object PhoneticsPreferences {

    private const val FILE = "glosso_display"
    private const val KEY_VISIBLE = "show_phonetics"

    private var prefs: KeyValueStore? = null

    private val _visible = MutableStateFlow(true)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Reads the saved choice once; safe to call from any screen. */
    fun init(context: PlatformContext) {
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _visible.value = loaded.getBoolean(KEY_VISIBLE, true)
    }

    fun setVisible(context: PlatformContext, value: Boolean) {
        init(context)
        if (_visible.value == value) return
        _visible.value = value
        prefs?.edit { putBoolean(KEY_VISIBLE, value) }
    }
}
