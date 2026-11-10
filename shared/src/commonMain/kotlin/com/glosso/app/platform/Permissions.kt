package com.glosso.app.platform

import androidx.compose.runtime.Composable

/** The runtime permissions the app asks for, each at the moment it is needed. */
enum class AppPermission {
    /** Recording a take. */
    MICROPHONE,

    /** Following a download from outside the app (Android 13+). */
    NOTIFICATIONS,
}

/** One permission's state and the system prompt for it. */
interface PermissionRequest {
    /** True when the app may use it now (or the platform does not ask for it). */
    fun isGranted(): Boolean

    /** Shows the system prompt; the answer goes to the `onResult` it was built with. */
    fun request()
}

/** A [PermissionRequest] for [permission] that reports each answer to [onResult]. */
@Composable
expect fun rememberPermissionRequest(permission: AppPermission, onResult: (granted: Boolean) -> Unit): PermissionRequest
