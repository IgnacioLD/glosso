package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// The system prompt shows NSMicrophoneUsageDescription from iosApp/Glosso (InfoPlist.strings
// in the four languages). TODO(ios): see the prompt and a denial on a device.
@Composable
actual fun rememberPermissionRequest(
    permission: AppPermission,
    onResult: (granted: Boolean) -> Unit,
): PermissionRequest {
    val callback = rememberUpdatedState(onResult)
    return remember(permission) {
        object : PermissionRequest {
            override fun isGranted(): Boolean = when (permission) {
                AppPermission.MICROPHONE ->
                    AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted
                // Downloads on iOS run in a background URLSession and need no
                // notification. TODO(ios phase 3): ask for it with the daily reminder.
                AppPermission.NOTIFICATIONS -> true
            }

            override fun request() {
                when (permission) {
                    AppPermission.MICROPHONE -> AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                        dispatch_async(dispatch_get_main_queue()) { callback.value(granted) }
                    }
                    AppPermission.NOTIFICATIONS -> callback.value(true)
                }
            }
        }
    }
}
