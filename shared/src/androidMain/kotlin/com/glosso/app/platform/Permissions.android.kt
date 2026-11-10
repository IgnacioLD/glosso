package com.glosso.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberPermissionRequest(
    permission: AppPermission,
    onResult: (granted: Boolean) -> Unit,
): PermissionRequest {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    return remember(context, permission, launcher) {
        object : PermissionRequest {
            override fun isGranted(): Boolean = isPermissionGranted(context, permission)

            override fun request() {
                manifestName(permission)?.let(launcher::launch)
            }
        }
    }
}

/** Whether [permission] is granted; true where Android does not ask for it. */
fun isPermissionGranted(context: Context, permission: AppPermission): Boolean {
    val name = manifestName(permission) ?: return true
    return ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}

/** The manifest permission, or null when this Android version grants it without asking. */
private fun manifestName(permission: AppPermission): String? = when (permission) {
    AppPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    AppPermission.NOTIFICATIONS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
}
