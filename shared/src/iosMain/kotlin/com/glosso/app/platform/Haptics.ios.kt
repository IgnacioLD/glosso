package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

// TODO(ios): not run yet (no Xcode); check the strength of each cue on an iPhone.
@Composable
actual fun rememberHaptics(): Haptics = remember { IosHaptics() }

private class IosHaptics : Haptics {
    private val impact = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    private val selection = UISelectionFeedbackGenerator()
    private val notification = UINotificationFeedbackGenerator()

    override fun press() = impact.impactOccurred()

    override fun tick() = selection.selectionChanged()

    override fun answer(correct: Boolean) = notification.notificationOccurred(
        if (correct) {
            UINotificationFeedbackType.UINotificationFeedbackTypeSuccess
        } else {
            UINotificationFeedbackType.UINotificationFeedbackTypeError
        },
    )
}
