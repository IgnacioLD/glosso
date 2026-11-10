package com.glosso.app

import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.observeUiLanguage
import com.glosso.app.platform.platformInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * The interface language as the app sees it, kept in sync with configuration
 * changes. ViewModels outlive the activity recreation a locale change causes, so
 * a plain read at construction would leave server-provided labels stale: screens
 * observe [current] and reload when it changes.
 */
object UiLanguage {

    private val _current = MutableStateFlow("en")
    val current: StateFlow<String> = _current.asStateFlow()

    private var observing = false

    fun init(context: PlatformContext) {
        // Refresh the value on every call: the activity is recreated on a locale
        // change, and this makes the new value available immediately.
        _current.value = platformInfo(context).uiLanguage
        if (observing) return
        observing = true
        observeUiLanguage(context) { _current.value = it }
    }
}

/**
 * Emits once per relevant language change: the target language (a different
 * content bank), the interface language (different server labels) or the accent
 * (the content is worded per accent), or the server. Combined so a screen reloads for all. Pure flows, so the trigger is testable without
 * Android resources.
 */
fun contentReloadSignal(
    targetLanguage: Flow<String>,
    uiLanguage: Flow<String>,
    accent: Flow<String> = VoicePreferences.accentFlow,
    server: Flow<String> = ServerPreferences.base,
): Flow<Unit> = combine(targetLanguage, uiLanguage, accent, server) { _, _, _, _ -> }
