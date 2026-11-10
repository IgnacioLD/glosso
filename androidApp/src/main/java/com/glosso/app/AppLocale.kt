package com.glosso.app

import android.content.Context
import android.os.LocaleList

/*
 * The in-app language picker (Android 13+, `LocaleManager`) is behind the shared
 * `PlatformActions` (`appLocale`, `setAppLocale`); the tags it offers match
 * `res/xml/locales_config.xml`.
 */

/**
 * Compose Multiplatform resolves a string in composition from Compose's
 * `Locale.current`, which is the first entry of the process default [LocaleList]
 * as the system set it. Android resources resolve against the activity
 * configuration instead, whose first locale is the best match among the languages
 * the app ships: with a preference list such as [pt-BR, es] the interface is in
 * Spanish, while the default list still starts with pt-BR and the shared strings
 * would fall back to English. Making the configuration's list the default keeps
 * both on the same language. With a supported first language the two lists are
 * already equal and nothing changes. The activity is recreated on every locale
 * change, so calling this from `onCreate` is enough.
 */
internal fun alignDefaultLocales(context: Context) {
    val resolved = context.resources.configuration.locales
    if (!resolved.isEmpty && resolved != LocaleList.getDefault()) LocaleList.setDefault(resolved)
}
