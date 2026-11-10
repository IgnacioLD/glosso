package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the meaning of the phrase is shown under it. Off by default: with the
 * translation always there the eye lands on the known language and stops working
 * on the new one, so it is a crutch, not help. It is one flow shared by every
 * card and the Settings switch, so the inline reveal and the switch never
 * disagree, and the learner who asks for it keeps it until they turn it off.
 */
object TranslationPreferences {

    private const val FILE = "glosso_display"
    private const val KEY_VISIBLE = "show_translations"

    private var prefs: KeyValueStore? = null

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Reads the saved choice once; safe to call from any screen. */
    fun init(context: PlatformContext) {
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _visible.value = loaded.getBoolean(KEY_VISIBLE, false)
    }

    fun setVisible(context: PlatformContext, value: Boolean) {
        init(context)
        if (_visible.value == value) return
        _visible.value = value
        prefs?.edit { putBoolean(KEY_VISIBLE, value) }
    }
}
