package com.glosso.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.glosso.app.platform.AppPermission
import com.glosso.app.platform.currentPlatformContext
import com.glosso.app.platform.rememberPermissionRequest
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Asks for the notification permission in context, at the moment the learner
 * starts a download rather than at launch. The rationale comes first because the
 * point is not obvious: the notification is what lets them follow, pause or
 * cancel the download after leaving the app. Starting the download is never
 * blocked on the answer; without the permission it simply runs without a
 * notification.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = currentPlatformContext()
    var showRationale by remember { mutableStateOf(false) }
    val permission = rememberPermissionRequest(AppPermission.NOTIFICATIONS) { granted ->
        Analytics.track("notification_permission", mapOf("granted" to granted))
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(Res.string.download_notification_permission_title)) },
            text = { Text(stringResource(Res.string.download_notification_permission_message)) },
            confirmButton = {
                TextButton(colors = accentTextButtonColors(), onClick = {
                    showRationale = false
                    DownloadPermission.markAsked(context)
                    permission.request()
                }) { Text(stringResource(Res.string.download_notification_permission_allow)) }
            },
            dismissButton = {
                TextButton(colors = accentTextButtonColors(), onClick = {
                    showRationale = false
                    DownloadPermission.markAsked(context)
                }) { Text(stringResource(Res.string.download_notification_permission_later)) }
            },
        )
    }

    return remember(context, permission) {
        {
            if (!permission.isGranted() && !DownloadPermission.wasAsked(context)) {
                showRationale = true
            }
        }
    }
}
