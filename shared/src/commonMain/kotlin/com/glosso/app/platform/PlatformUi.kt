package com.glosso.app.platform

import androidx.compose.runtime.Composable

/**
 * The [PlatformContext] of the composition: the Activity's context on Android,
 * the single instance on iOS. For the stores and settings that take one.
 */
@Composable
expect fun currentPlatformContext(): PlatformContext

/**
 * The interface language of the composition ("es"), "en" when unknown. On
 * Android it is the Activity configuration's first locale, which the system
 * resolves against the languages the app ships.
 */
@Composable
expect fun currentUiLanguage(): String

/** True when the system asks for no animation (Android: animator duration scale 0). */
@Composable
expect fun rememberReduceMotion(): Boolean

/** The things a screen asks the system to do outside the app. */
interface PlatformActions {
    /** Opens this app's page in the system settings (to grant a denied permission). */
    fun openAppSettings()

    /**
     * True when the system can hold a per-app interface language the app can set
     * (Android 13+). Elsewhere the app follows the system language and the
     * in-app picker is not shown.
     */
    val appLocaleSupported: Boolean

    /** The app-level language override, or "" when the app follows the system. */
    fun appLocale(): String

    /** Sets the app-level language; "" follows the system again. */
    fun setAppLocale(tag: String)

    /**
     * True when the system keeps the app's interface language on the app's own
     * settings page ([openAppSettings]) instead of letting the app set it (iOS).
     */
    val appLocaleInSystemSettings: Boolean get() = false
}

@Composable
expect fun rememberPlatformActions(): PlatformActions

/** The language tags the in-app picker offers; "" means "follow the system". */
val APP_LOCALE_TAGS = listOf("", "en", "es", "fr", "de")
