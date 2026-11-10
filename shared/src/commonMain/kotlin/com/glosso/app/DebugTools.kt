package com.glosso.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Debug-only screens the shared UI links to. They stay in the platform app (on
 * Android in `src/debug`, with a no-op twin in `src/release`), so release never
 * carries their code; the shared screens only ask whether one is available.
 */
interface DebugTools {
    /** True when the on-device model comparison can be opened from Settings. */
    val modelCompareAvailable: Boolean

    @Composable
    fun ModelCompareScreen(onBack: () -> Unit)
}

/** No debug screens (release builds, iOS for now). */
object NoDebugTools : DebugTools {
    override val modelCompareAvailable: Boolean get() = false

    @Composable
    override fun ModelCompareScreen(onBack: () -> Unit) = Unit
}

/** The platform's [DebugTools], provided by the host; none by default. */
val LocalDebugTools = staticCompositionLocalOf<DebugTools> { NoDebugTools }
