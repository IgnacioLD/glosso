package com.glosso.app.debugtools

import androidx.compose.runtime.Composable

/**
 * Release has no model comparison. These symbols exist only so the settings
 * screen compiles: the flag is always false, so the entry point is never shown,
 * and the comparator (second model, download, PER) lives only in the debug
 * source set and is absent from a release APK.
 */
internal fun modelCompareAvailable(): Boolean = false

@Composable
internal fun ModelCompareScreen(onBack: () -> Unit) {
}
