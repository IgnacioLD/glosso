package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

@Composable
actual fun currentPlatformContext(): PlatformContext = PlatformContext.INSTANCE

@Composable
actual fun currentUiLanguage(): String = remember { platformInfo(PlatformContext.INSTANCE).uiLanguage }

@Composable
actual fun rememberReduceMotion(): Boolean = remember { window.matchMedia("(prefers-reduced-motion: reduce)").matches }

@Composable
actual fun rememberPlatformActions(): PlatformActions = remember { WebPlatformActions }

private object WebPlatformActions : PlatformActions {
    // A page cannot open the browser's site settings; the screen still explains the denial.
    override fun openAppSettings() = Unit

    // The interface follows the browser's language list, as iOS follows the system.
    override val appLocaleSupported: Boolean get() = false

    override fun appLocale(): String = ""

    override fun setAppLocale(tag: String) = Unit
}
