package com.glosso.app

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString as resourceString
import java.util.Locale

/**
 * Reads a Compose Multiplatform string in [locale]. Outside composition the
 * library resolves the language from the default locale, which is what the
 * platform sets for the app's interface language, so the test sets it too.
 */
internal class LocalizedStrings(private val locale: Locale) {
    fun getString(res: StringResource, vararg args: Any): String {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return runBlocking { if (args.isEmpty()) resourceString(res) else resourceString(res, *args) }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
