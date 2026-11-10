package com.glosso.app.platform

import androidx.compose.runtime.Composable

/**
 * Runs [onBack] when the system back gesture or button is used while [enabled].
 * Android: Compose's `BackHandler`. iOS has no system back: a no-op for now, the
 * edge swipe and a visible back button come with the iOS app (phase 4).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
