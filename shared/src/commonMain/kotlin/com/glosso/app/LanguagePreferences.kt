package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.engine.model.LanguagePack
import com.glosso.engine.model.LanguagePacks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The target language the learner is studying. Process-wide so the API, the
 * asset URLs and every screen agree, and persisted so the choice survives a
 * restart. Changing it re-points content, voices and scoring at another
 * `content/<code>` bank.
 */
object LanguagePreferences {
    private const val FILE = "glosso_language"
    private const val KEY = "target_language"

    private lateinit var prefs: KeyValueStore

    private val _code = MutableStateFlow(LanguagePacks.default.code)
    val code: StateFlow<String> = _code.asStateFlow()

    /** The current pack; never null, falls back to English. */
    val current: LanguagePack get() = LanguagePacks.byCode(_code.value)

    fun init(context: PlatformContext) {
        prefs = keyValueStore(context, FILE)
        _code.value = OfferedLanguages.orNull(prefs.getString(KEY))?.code
            ?: LanguagePacks.default.code
    }

    fun set(code: String) {
        val pack = OfferedLanguages.orNull(code) ?: return
        if (pack.code == _code.value) return
        _code.value = pack.code
        if (::prefs.isInitialized) prefs.edit { putString(KEY, pack.code) }
    }
}

/**
 * The meaning of a content item for the interface language, or null when the
 * content does not carry one. It never falls back to another language: a Spanish
 * line under an English interface reads as a bug, so a missing translation is
 * better hidden than shown in the wrong language.
 */
fun Map<String, String>.translationFor(uiLanguage: String): String? =
    this[uiLanguage]?.takeIf { it.isNotBlank() }
