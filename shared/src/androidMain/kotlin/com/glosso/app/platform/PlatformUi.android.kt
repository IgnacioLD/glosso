package com.glosso.app.platform

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun currentPlatformContext(): PlatformContext = LocalContext.current

@Composable
actual fun currentUiLanguage(): String = LocalContext.current.resources.configuration.uiLanguage()

@Composable
actual fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

@Composable
actual fun ScreenshotProtection() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window.setScreenshotProtected(true)
        onDispose { window.setScreenshotProtected(false) }
    }
}

/** `Window.FLAG_SECURE` on/off; a null window is a no-op. */
fun Window?.setScreenshotProtected(protected: Boolean) {
    if (this == null) return
    if (protected) {
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable
actual fun rememberPlatformActions(): PlatformActions {
    val context = LocalContext.current
    return remember(context) { AndroidPlatformActions(context) }
}

private class AndroidPlatformActions(private val context: Context) : PlatformActions {

    override fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun openSubscriptionManagement(productId: String) {
        val url = "https://play.google.com/store/account/subscriptions" +
            "?sku=$productId&package=${context.packageName}"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override val appLocaleSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun appLocale(): String =
        if (appLocaleSupported) currentAppLocale(context) else ""

    override fun setAppLocale(tag: String) {
        if (appLocaleSupported) setAppLocale(context, tag)
    }
}

/*
 * Interface language selection for Android 13+, backed by the platform's
 * per-app language API. The tags offered match `res/xml/locales_config.xml`.
 * An empty tag means "follow the system"; it clears the per-app override.
 *
 * Below API 33 the OS has no per-app language concept, and the app is a
 * Compose-only `ComponentActivity` on a Material 3 theme. `AppCompatDelegate`
 * would only apply its stored locale to AppCompat activities on a Theme.AppCompat,
 * so making the picker work on 26-32 would mean pulling a second theming system
 * into the app. Instead the UI language follows the system locale there, which is
 * the platform's own behaviour; the in-app picker is simply not shown.
 */

/** The app-level locale override, or "" when the app follows the system. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun currentAppLocale(context: Context): String {
    val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
    return locales?.takeIf { !it.isEmpty }?.get(0)?.language ?: ""
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun setAppLocale(context: Context, tag: String) {
    val manager = context.getSystemService(LocaleManager::class.java) ?: return
    manager.applicationLocales =
        if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}

actual typealias PlatformActivity = Activity

@Composable
actual fun currentPlatformActivity(): PlatformActivity? = LocalContext.current.findActivity()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    else -> (this as? ContextWrapper)?.baseContext?.findActivity()
}
