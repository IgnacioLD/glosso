package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.glosso.app.audio.microphoneAllowed
import com.glosso.app.audio.requestMicrophone
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

// The browser's own prompt; a site the learner blocked stays blocked until they
// allow it in the browser's site settings.
@Composable
actual fun rememberPermissionRequest(
    permission: AppPermission,
    onResult: (granted: Boolean) -> Unit,
): PermissionRequest {
    val callback = rememberUpdatedState(onResult)
    return remember(permission) {
        object : PermissionRequest {
            override fun isGranted(): Boolean = when (permission) {
                AppPermission.MICROPHONE -> microphoneAllowed()
                // Nothing downloads in the background on the web.
                AppPermission.NOTIFICATIONS -> true
            }

            override fun request() {
                when (permission) {
                    AppPermission.MICROPHONE -> MainScope().launch {
                        callback.value(requestMicrophone().await<JsBoolean>().toBoolean())
                    }
                    AppPermission.NOTIFICATIONS -> callback.value(true)
                }
            }
        }
    }
}
