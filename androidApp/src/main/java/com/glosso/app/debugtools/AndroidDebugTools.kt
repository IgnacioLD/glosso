package com.glosso.app.debugtools

import androidx.compose.runtime.Composable
import com.glosso.app.DebugTools

/**
 * The Android debug screens behind the shared [DebugTools]. The symbols it calls
 * exist in both variants: the comparison itself in `src/debug`, a no-op twin in
 * `src/release` that reports it unavailable.
 */
object AndroidDebugTools : DebugTools {
    override val modelCompareAvailable: Boolean get() = modelCompareAvailable()

    @Composable
    override fun ModelCompareScreen(onBack: () -> Unit) = com.glosso.app.debugtools.ModelCompareScreen(onBack)
}
