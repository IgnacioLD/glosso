package com.glosso.app.platform

import androidx.compose.runtime.Composable

/** The few touch cues the app gives. Each one is a no-op where the device has none. */
interface Haptics {
    /** A firm press: the microphone tap, a scored take. */
    fun press()

    /** A light tick: moving to another tab. */
    fun tick()

    /** A confirm or a reject cue for an answered exercise. */
    fun answer(correct: Boolean)
}

/** The haptics of the current window. */
@Composable
expect fun rememberHaptics(): Haptics
