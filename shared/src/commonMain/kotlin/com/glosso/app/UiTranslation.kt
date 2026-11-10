package com.glosso.app

import androidx.compose.runtime.Composable
import com.glosso.app.platform.currentUiLanguage

/**
 * The meaning of the current item in the interface language, or null when the
 * content carries none. Never falls back to another language: with the interface
 * in English, a Spanish line under the phrase reads as a bug, so the line is
 * hidden instead.
 */
@Composable
fun uiTranslation(translations: Map<String, String>): String? =
    translations.translationFor(currentUiLanguage())
