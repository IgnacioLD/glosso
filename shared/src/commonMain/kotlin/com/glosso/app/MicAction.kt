package com.glosso.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.glosso.app.platform.AppPermission
import com.glosso.app.platform.rememberHaptics
import com.glosso.app.platform.rememberPermissionRequest

class MicAction(val onMic: () -> Unit, val permissionDenied: Boolean)

/** The microphone tap with its permission flow, shared by every recorder. */
@Composable
fun rememberMicAction(onToggle: () -> Unit): MicAction {
    val haptics = rememberHaptics()
    var denied by remember { mutableStateOf(false) }
    val permission = rememberPermissionRequest(AppPermission.MICROPHONE) { granted ->
        denied = !granted
        if (granted) onToggle() else Analytics.track("mic_permission_denied", mapOf("flow" to "record"))
    }
    return MicAction(
        onMic = {
            if (permission.isGranted()) {
                denied = false
                haptics.press()
                onToggle()
            } else {
                permission.request()
            }
        },
        permissionDenied = denied,
    )
}
