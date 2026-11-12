package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.glosso.app.audio.vibrate

// navigator.vibrate: Android browsers buzz, iOS Safari and desktops do nothing.
@Composable
actual fun rememberHaptics(): Haptics = remember { WebHaptics }

private object WebHaptics : Haptics {
    override fun press() = vibrate(20)

    override fun tick() = vibrate(8)

    override fun answer(correct: Boolean) = vibrate(if (correct) 15 else 40)
}
