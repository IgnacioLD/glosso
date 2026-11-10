package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@Composable
actual fun currentPlatformContext(): PlatformContext = PlatformContext.INSTANCE

@Composable
actual fun currentUiLanguage(): String = remember { platformInfo(PlatformContext.INSTANCE).uiLanguage }

@Composable
actual fun rememberReduceMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

// TODO(iOS phase 4): hide the paywall from the app switcher snapshot. iOS cannot
// block screenshots; there is no paywall on iOS during the beta.
@Composable
actual fun ScreenshotProtection() = Unit

@Composable
actual fun rememberPlatformActions(): PlatformActions = remember { IosPlatformActions }

private object IosPlatformActions : PlatformActions {
    override fun openAppSettings() = open(UIApplicationOpenSettingsURLString)

    override fun openSubscriptionManagement(productId: String) =
        open("https://apps.apple.com/account/subscriptions")

    // iOS keeps the per-app language in the Settings app, not in an API the app sets.
    override val appLocaleSupported: Boolean get() = false

    override fun appLocale(): String = ""

    override fun setAppLocale(tag: String) = Unit

    // Settings > Apps > Glosso > Language, listed because the bundle has four localizations.
    override val appLocaleInSystemSettings: Boolean get() = true

    private fun open(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any?>(), null)
    }
}

/** No purchase flow on iOS during the beta (docs/IOS-KMP-PLAN.md, decision 4). */
actual open class PlatformActivity

@Composable
actual fun currentPlatformActivity(): PlatformActivity? = null
