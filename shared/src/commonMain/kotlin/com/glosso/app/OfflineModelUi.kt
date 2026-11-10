package com.glosso.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.download.DownloadItemState
import com.glosso.app.download.DownloadStatus
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/** The real figure the app knows for the model before it is on disk. */
internal fun onDeviceModelSize(): String = formatBytes(OnDeviceScoring.APPROX_BYTES)

/**
 * The one-time tip shown inside the offline strip: it explains that the model
 * can be downloaded so practice keeps working with no connection, and offers the
 * action right there. It is not a loop; the caller hides it for good once the
 * learner closes it or starts the download.
 */
@Composable
internal fun OfflineModelNudge(
    sizeText: String,
    onDownload: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.x4, bottom = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.offline_nudge_body, sizeText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(colors = accentTextButtonColors(), onClick = onDownload) {
            Text(stringResource(Res.string.offline_nudge_cta), style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = onHide) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(Res.string.offline_nudge_hide),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The on-device scoring pack as one control: it names its state (not downloaded,
 * downloading with a progress, ready with its real size) and offers the one
 * action that fits it. Shared so Settings, the offline drawer and the onboarding
 * offer read and behave the same. The delete confirmation lives here too, so no
 * screen invents its own.
 */
@Composable
internal fun OnDevicePackControl(
    state: OnDeviceScorerState,
    item: DownloadItemState?,
    sizeText: String,
    onDownload: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(Res.string.settings_delete_score_title)) },
            text = { Text(stringResource(Res.string.settings_delete_score_message)) },
            confirmButton = {
                TextButton(colors = accentTextButtonColors(), onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(colors = accentTextButtonColors(), onClick = { confirmRemove = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    when {
        // On a cold start the pack's presence is not known yet: a neutral block
        // holds its place instead of claiming "not downloaded" and then flipping.
        !state.loaded -> SkeletonBlock(Modifier.fillMaxWidth().height(48.dp))

        state.downloading -> {
            ProgressLine(state.progress)
            Spacer(Modifier.height(Space.x8))
            Text(
                text = if (item != null && item.status == DownloadStatus.WAITING_NETWORK) {
                    stringResource(Res.string.download_waiting_wifi)
                } else {
                    stringResource(Res.string.settings_downloading, (state.progress * 100).toInt())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = GlossoColors.accentText,
            )
            if (item != null) {
                Spacer(Modifier.height(Space.x4))
                DownloadControls(item)
            }
        }

        state.preparing -> Text(
            text = stringResource(Res.string.settings_preparing),
            style = MaterialTheme.typography.bodyMedium,
            color = GlossoColors.accentText,
        )

        state.present -> {
            Text(
                text = stringResource(Res.string.settings_ready),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x2))
            Text(
                text = formatBytes(state.bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRemove != null) {
                Spacer(Modifier.height(Space.x16))
                SecondaryButton(
                    onClick = { confirmRemove = true },
                    contentColor = GlossoColors.danger,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.lessons_delete_device), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        else -> {
            Text(
                text = stringResource(Res.string.offline_model_not_downloaded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x12))
            SecondaryButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.settings_download_model, sizeText),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    state.error?.let {
        Spacer(Modifier.height(Space.x8))
        Text(it, style = MaterialTheme.typography.bodySmall, color = GlossoColors.danger)
    }
}

/**
 * The same pack as a bordered card, for the surfaces that are not a Settings
 * section: the offline drawer and the first-run offer. The description names
 * what it buys; the control below carries the state and the action.
 */
@Composable
internal fun OnDevicePackCard(
    state: OnDeviceScorerState,
    item: DownloadItemState?,
    sizeText: String,
    onDownload: () -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = cardBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.DownloadForOffline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.icon),
                )
                Spacer(Modifier.width(Space.x12))
                Text(
                    text = stringResource(Res.string.settings_ondevice),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(Res.string.offline_model_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x16))
            OnDevicePackControl(state, item, sizeText, onDownload, onRemove)
        }
    }
}
