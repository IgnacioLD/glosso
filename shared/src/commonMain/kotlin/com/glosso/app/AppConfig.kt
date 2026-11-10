package com.glosso.app

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the build decides and the shared code reads: the debug tools, the store
 * capture mode, the experimental rhythm bonus and the version. Android fills it
 * from `BuildConfig`; iOS will from its bundle and build settings. Screens read [LocalAppConfig]; ViewModels get the fields
 * they need in their constructor.
 */
data class AppConfig(
    /** Debug-only tools (take injection, fixtures, model comparison). Never on in release. */
    val debug: Boolean = false,
    /** Store-capture build: hides the debug affordances so captures look like release. */
    val storeScreenshots: Boolean = false,
    /** The separate rhythm-and-intonation bonus (off by default). */
    val prosodyBonus: Boolean = false,
    val versionName: String = "",
    val versionCode: Int = 0,
    /**
     * The usage statistics section of Settings. Off on iOS, where analytics stay
     * uninitialised until the privacy policy and the App Store forms cover it
     * (docs/IOS-KMP-PLAN.md, phase 6): its switch would turn them on.
     */
    val usageStatistics: Boolean = true,
) {
    /** The debug affordances drawn on screen: debug builds that are not taking store captures. */
    val debugUi: Boolean get() = debug && !storeScreenshots
}

/** The build's [AppConfig], provided by the host around the whole UI. */
val LocalAppConfig = staticCompositionLocalOf<AppConfig> { error("LocalAppConfig is not provided") }
