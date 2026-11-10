package com.glosso.app

import com.glosso.app.download.LocalDownloader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.Space
import com.glosso.app.download.DownloadFailure
import com.glosso.app.download.DownloadItemState
import com.glosso.app.download.DownloadStatus
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * In-app controls for a running download, so the learner can pause, resume or
 * cancel without hunting for the notification. The same three verbs the
 * notification offers, reading the same queue.
 */
@Composable
internal fun DownloadControls(item: DownloadItemState, modifier: Modifier = Modifier) {
    val downloader = LocalDownloader.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                QuietTextButton(stringResource(Res.string.download_pause), onClick = { downloader.pause(item.id) })

            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                QuietTextButton(stringResource(Res.string.download_resume), onClick = { downloader.resume(item.id) })

            DownloadStatus.WAITING_NETWORK ->
                QuietTextButton(
                    stringResource(Res.string.download_allow_metered),
                    onClick = { downloader.allowMetered(item.id) },
                )

            else -> Unit
        }
        if (item.status != DownloadStatus.DONE && item.status != DownloadStatus.CANCELLED) {
            QuietTextButton(stringResource(Res.string.download_cancel), onClick = { downloader.cancel(item.id) })
        }
    }
}

/** The honest one-line status for a queued or running item. */
@Composable
internal fun downloadStatusText(item: DownloadItemState): String = when (item.status) {
    DownloadStatus.WAITING_NETWORK -> stringResource(Res.string.download_waiting_wifi)
    DownloadStatus.PAUSED -> stringResource(Res.string.download_paused)
    DownloadStatus.QUEUED -> stringResource(Res.string.download_queued)
    DownloadStatus.FAILED -> stringResource((item.failure ?: DownloadFailure.GENERIC).message)
    DownloadStatus.DONE -> stringResource(Res.string.download_complete_title)
    DownloadStatus.CANCELLED -> stringResource(Res.string.download_cancelled)
    DownloadStatus.RUNNING -> {
        val total = item.bytesTotal
        if (total != null && total > 0) {
            stringResource(Res.string.download_progress_bytes, formatBytes(item.bytesWritten), formatBytes(total))
        } else {
            formatBytes(item.bytesWritten)
        }
    }
}

/** A compact block for one active download, for the offline drawer. */
@Composable
internal fun ActiveDownloadRow(item: DownloadItemState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.x12)) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.x8))
        ProgressLine(item.progress)
        Spacer(Modifier.height(Space.x8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                downloadStatusText(item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            DownloadControls(item)
        }
    }
}
