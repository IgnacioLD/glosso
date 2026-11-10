package com.glosso.app

import com.glosso.app.download.DownloadIds
import com.glosso.app.download.LocalDownloader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
/**
 * The offline drawer: what is on the device, how much room it takes, and the one
 * place to free it. Downloads follow the content frontier - free lessons for a
 * free learner, any lesson with a subscription - so this screen never assumes the
 * learner is Pro. The gate lives on each lesson's download control.
 */
@Composable
fun OfflineScreen(
    vm: LessonViewModel,
    onBack: () -> Unit,
    scoringVm: ScoringViewModel = scoringViewModel(),
) {
    val offline by OfflineStore.state.collectAsState()
    val lessonState by vm.state.collectAsState()
    val downloads by LocalDownloader.current.state.collectAsState()
    val scorer by scoringVm.state.collectAsState()
    val active = downloads.activeLessons
    val labels = lessonState.themeLabels
    val modelItem = downloads.item(DownloadIds.MODEL)
    val modelSize = onDeviceModelSize()
    val modelSupported = LocalAppServices.current.onDevice.supported
    val requestNotifications = rememberNotificationPermissionRequest()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(Res.string.offline_confirm_title)) },
            text = { Text(stringResource(Res.string.offline_confirm_message)) },
            confirmButton = {
                TextButton(colors = accentTextButtonColors(), onClick = {
                    vm.clearDownloads()
                    confirmClear = false
                }) { Text(stringResource(Res.string.offline_delete_all)) }
            },
            dismissButton = {
                TextButton(colors = accentTextButtonColors(), onClick = { confirmClear = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(title = stringResource(Res.string.offline_title), onBack = onBack)

        when {
            !offline.loaded ->
                LoadingState(Modifier.weight(1f))

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = listBottomPadding()),
            ) {
                // The scoring model is the other half of "study without a
                // connection": it is offered here, with its state, even when no
                // lesson has been downloaded yet.
                if (modelSupported) item {
                    OnDevicePackCard(
                        state = scorer,
                        item = modelItem,
                        sizeText = modelSize,
                        onDownload = {
                            scoringVm.download()
                            requestNotifications()
                        },
                        onRemove = { scoringVm.delete() },
                    )
                    Spacer(Modifier.height(Space.x24))
                    Hairline()
                    Spacer(Modifier.height(Space.x24))
                }

                if (active.isNotEmpty()) {
                    item {
                        SectionLabel(stringResource(Res.string.offline_downloading_section))
                        Spacer(Modifier.height(Space.x4))
                        active.forEach { item -> ActiveDownloadRow(item) }
                        Spacer(Modifier.height(Space.x12))
                        Hairline()
                        Spacer(Modifier.height(Space.x20))
                    }
                }
                if (offline.metas.isNotEmpty()) {
                    item {
                        SpaceCard(bytes = offline.totalBytes, count = offline.metas.size) { confirmClear = true }
                        Spacer(Modifier.height(Space.x20))
                        SectionLabel(stringResource(Res.string.offline_section))
                        Spacer(Modifier.height(Space.x4))
                    }
                    items(offline.metas, key = { it.id }) { meta ->
                        OfflineRow(meta, labels) { vm.deleteDownload(meta.id) }
                        Hairline()
                    }
                } else if (active.isEmpty()) {
                    item {
                        Spacer(Modifier.height(Space.x8))
                        EmptyState(
                            title = stringResource(Res.string.offline_empty_title),
                            message = stringResource(Res.string.offline_empty_message),
                            modifier = Modifier.fillMaxWidth().padding(vertical = Space.x24),
                            icon = Icons.Outlined.DownloadForOffline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * How much the device holds, stacked so the size and its caption each get the
 * full width, with the one way to free it below a hairline instead of squeezed
 * beside the number.
 */
@Composable
private fun SpaceCard(bytes: Long, count: Int, onFree: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = Space.x20, end = Space.x20, top = Space.x20, bottom = Space.x16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(Space.x16))
                Column(Modifier.weight(1f)) {
                    Text(
                        formatBytes(bytes),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        pluralStringResource(Res.plurals.offline_in_device, count, count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Hairline()
            TextButton(
                onClick = onFree,
                colors = ButtonDefaults.textButtonColors(contentColor = GlossoColors.danger),
                modifier = Modifier.padding(horizontal = Space.x8, vertical = Space.x4),
            ) {
                Text(stringResource(Res.string.offline_free_space), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun OfflineRow(meta: OfflineMeta, labels: Map<String, String>, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.label_pair, themeLabel(meta.theme, labels), meta.level),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x8))
            Text(
                meta.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.x4))
            Text(
                formatBytes(meta.bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(Res.string.offline_delete_download),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
