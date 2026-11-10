package com.glosso.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceStyle

actual fun platformInfo(context: PlatformContext): PlatformInfo = IosPlatformInfo

private object IosPlatformInfo : PlatformInfo {
    override val osName: String get() = PlatformInfo.IOS
    @OptIn(ExperimentalForeignApi::class)
    override val osMajorVersion: Int
        get() = NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion.toInt() }
    override val appVersion: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
    override val isTablet: Boolean
        get() = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad

    // TODO(ios): check on a device that the key window is there when app_open is
    // tracked; before the first window this is null and the property is skipped.
    override val isDarkMode: Boolean?
        get() = UIApplication.sharedApplication.keyWindow?.traitCollection?.userInterfaceStyle
            ?.let { it == UIUserInterfaceStyle.UIUserInterfaceStyleDark }

    /**
     * The first preferred language of the app, which iOS resolves against the
     * localizations the bundle ships (the per-app language in Settings included).
     * iosApp/Glosso/Info.plist declares en, es, fr and de (CFBundleLocalizations).
     */
    override val uiLanguage: String
        get() = (NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String)
            ?.let { NSLocale(localeIdentifier = it).languageCode }
            ?.takeIf { it.isNotBlank() }
            ?: (NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-')?.takeIf { it.isNotBlank() }
            ?: "en"
}

/**
 * Nothing to observe: iOS relaunches the app when its language changes in
 * Settings, so the value read at start stays right for the whole process.
 */
actual fun observeUiLanguage(context: PlatformContext, onChange: (String) -> Unit) = Unit
