package com.glosso.app.platform

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Build

actual fun platformInfo(context: PlatformContext): PlatformInfo = AndroidPlatformInfo(context)

private class AndroidPlatformInfo(private val context: PlatformContext) : PlatformInfo {
    override val osName: String get() = PlatformInfo.ANDROID
    override val osMajorVersion: Int get() = Build.VERSION.SDK_INT
    override val appVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    private val configuration: Configuration?
        get() = runCatching { context.resources.configuration }.getOrNull()

    override val isTablet: Boolean? get() = configuration?.let { it.smallestScreenWidthDp >= 600 }
    override val isDarkMode: Boolean?
        get() = configuration?.let {
            it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    override val uiLanguage: String get() = configuration.uiLanguage()
}

/** The first locale's language of [this] configuration, "en" when there is none. */
fun Configuration?.uiLanguage(): String =
    this?.locales?.get(0)?.language?.takeIf { it.isNotBlank() } ?: "en"

actual fun observeUiLanguage(context: PlatformContext, onChange: (String) -> Unit) {
    (context.applicationContext as Application).registerComponentCallbacks(object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = onChange(newConfig.uiLanguage())

        @Deprecated("Required by ComponentCallbacks; nothing to free here.")
        override fun onLowMemory() = Unit
    })
}
