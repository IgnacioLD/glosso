package com.glosso.app.platform

import com.glosso.app.WebBuild
import kotlinx.browser.window
import org.w3c.dom.events.Event

actual fun platformInfo(context: PlatformContext): PlatformInfo = WebPlatformInfo

private object WebPlatformInfo : PlatformInfo {
    override val osName: String get() = PlatformInfo.WEB
    override val osMajorVersion: Int get() = 0
    override val appVersion: String get() = WebBuild.VERSION_NAME
    override val isTablet: Boolean? get() = null
    override val isDarkMode: Boolean get() = window.matchMedia("(prefers-color-scheme: dark)").matches

    /** The first of the browser's languages the app ships (en, es, fr, de). */
    override val uiLanguage: String
        get() = browserLanguages().split(',').map { it.substringBefore('-').lowercase() }
            .firstOrNull { it in SHIPPED } ?: "en"

    private val SHIPPED = setOf("en", "es", "fr", "de")
}

actual fun observeUiLanguage(context: PlatformContext, onChange: (String) -> Unit) {
    window.addEventListener("languagechange", { _: Event -> onChange(WebPlatformInfo.uiLanguage) })
}

private fun browserLanguages(): String = js("(navigator.languages || [navigator.language || 'en']).join(',')")
