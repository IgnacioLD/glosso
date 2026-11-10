package com.glosso.app.platform

/**
 * Coarse facts about the setting the app runs in, for the analytics context and
 * the interface language. Never anything that identifies the device: no model,
 * brand or hardware id. Values that can change while the app runs (dark mode,
 * the UI language) are read on each access.
 */
interface PlatformInfo {
    /** [ANDROID] or [IOS]. */
    val osName: String

    /** Android: the API level (`Build.VERSION.SDK_INT`). iOS: the major system version. */
    val osMajorVersion: Int

    /** The app's version name, e.g. "2.5.4". */
    val appVersion: String

    /** Null when it cannot be read. */
    val isTablet: Boolean?

    /** Null when it cannot be read. */
    val isDarkMode: Boolean?

    /** The interface language's code ("es"), "en" when unknown. */
    val uiLanguage: String

    companion object {
        const val ANDROID = "android"
        const val IOS = "ios"
    }
}

expect fun platformInfo(context: PlatformContext): PlatformInfo

/**
 * Calls [onChange] with the interface language whenever it changes while the
 * app runs. Registered once per process by `UiLanguage`.
 */
expect fun observeUiLanguage(context: PlatformContext, onChange: (String) -> Unit)
