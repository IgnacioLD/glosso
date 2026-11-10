package com.glosso.app.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val view = LocalView.current
    return remember(feedback, view) { AndroidHaptics(feedback, view) }
}

private class AndroidHaptics(private val feedback: HapticFeedback, private val view: View) : Haptics {

    override fun press() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    override fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    /**
     * A confirm or reject haptic on API 30+, falling back to a long press on older
     * devices (Compose only exposes LongPress and TextHandleMove there).
     */
    override fun answer(correct: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(
                if (correct) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT,
            )
        } else {
            press()
        }
    }
}
