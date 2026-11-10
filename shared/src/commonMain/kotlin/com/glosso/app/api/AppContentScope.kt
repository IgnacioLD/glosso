package com.glosso.app.api

import com.glosso.app.LanguagePreferences
import com.glosso.app.UiLanguage
import com.glosso.app.VoicePreferences

/** The learner's choices as the app stores them, for every [GlossoApi] the app builds. */
object AppContentScope : ContentScope {
    override val language: String get() = LanguagePreferences.current.code
    override val accent: String get() = VoicePreferences.accent
    override val authoredAccent: String get() = LanguagePreferences.current.defaultAccent
    override val uiLanguage: String get() = UiLanguage.current.value
}
