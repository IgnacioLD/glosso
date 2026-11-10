package com.glosso.app.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.glosso.app.MainActivity
import com.glosso.app.R
import com.glosso.app.formatBytes
import com.glosso.app.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString as text

/**
 * Keeps a download alive when the learner leaves the app or turns the screen
 * off. It is a `dataSync` foreground service: the platform's own mechanism for
 * a user-initiated transfer that must continue in the background. The work
 * itself lives in [DownloadManager]; this service holds the process open and
 * draws the honest, actionable notification (progress, pause, resume, cancel),
 * grouped when several items are queued.
 *
 * Its words are the shared UI's (Compose Multiplatform resources, read with a
 * suspend call), so one renderer coroutine draws the latest queue state each
 * time it is asked to; requests made while it draws fold into one.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var started = false
    private var hadWork = false

    @Volatile
    private var postedItemIds: Set<Int> = emptySet()

    /** Render requests; conflated, so a burst of progress updates draws once. */
    private val renders = Channel<Unit>(Channel.CONFLATED)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
        // Raise the foreground at once with a neutral note; the real progress
        // replaces it on the first state emission. The system gives us only a
        // few seconds to call this, so it cannot wait for the queue.
        startForegroundCompat(preparingNotification())
        started = true
        hadWork = DownloadManager.state.value.hasWork
        scope.launch {
            for (request in renders) render(DownloadManager.state.value)
        }
        scope.launch {
            DownloadManager.state.collect { renders.trySend(Unit) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(DownloadNotifications.EXTRA_ITEM_ID)
        when (intent?.action) {
            DownloadNotifications.ACTION_PAUSE -> if (id != null) DownloadManager.pause(id) else DownloadManager.pauseAll()
            DownloadNotifications.ACTION_RESUME -> if (id != null) DownloadManager.resume(id) else DownloadManager.resumeAll()
            DownloadNotifications.ACTION_CANCEL -> if (id != null) DownloadManager.cancel(id) else DownloadManager.cancelAll()
            DownloadNotifications.ACTION_ALLOW_METERED -> id?.let { DownloadManager.allowMetered(it) }
        }
        renders.trySend(Unit)
        // The queue lives in memory, so a system restart with no queue would
        // only raise an empty service; do not ask for one.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------- rendering

    private suspend fun render(state: DownloadsState) {
        val active = state.active
        if (active.isEmpty()) {
            finish(state)
            return
        }
        hadWork = true
        startForegroundCompat(summaryNotification(state, active))

        val manager = NotificationManagerCompat.from(this)
        // A new download supersedes the previous "done" note.
        runCatching { manager.cancel(DownloadNotifications.COMPLETE_ID) }
        val multiple = active.size > 1
        val activeIds = active.map { DownloadNotifications.itemNotificationId(it.id) }.toSet()
        if (multiple) {
            active.forEach { notifySafely(DownloadNotifications.itemNotificationId(it.id), itemNotification(it)) }
        }
        (postedItemIds - activeIds).forEach { runCatching { manager.cancel(it) } }
        postedItemIds = if (multiple) activeIds else emptySet()
    }

    private suspend fun finish(state: DownloadsState) {
        val manager = NotificationManagerCompat.from(this)
        postedItemIds.forEach { runCatching { manager.cancel(it) } }
        postedItemIds = emptySet()
        if (hadWork) {
            hadWork = false
            completionNotification(state)?.let {
                notifySafely(DownloadNotifications.COMPLETE_ID, it)
            }
        }
        if (started) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Posts a notification, swallowing the case where POST_NOTIFICATIONS is denied
     * or revoked: the transfer must keep running without a visible note, never
     * crash. The explicit catch of SecurityException is what the MissingPermission
     * lint check recognises (a `runCatching` wrapper is opaque to it).
     */
    private fun notifySafely(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
            // No notification permission: the download continues silently.
        }
    }

    // --------------------------------------------------------------- builders

    private fun preparingNotification(): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.download_channel_name))
            .setProgress(0, 0, true)
            .build()

    /** One notification that reflects the queue: a single item, or the group. */
    private suspend fun summaryNotification(state: DownloadsState, active: List<DownloadItemState>): Notification {
        val primary = state.running.firstOrNull() ?: active.first()
        val builder = baseBuilder().setGroup(DownloadNotifications.GROUP_KEY)

        if (active.size > 1) {
            builder.setContentTitle(
                getPluralString(Res.plurals.download_group_title, active.size, active.size),
            )
            builder.setContentText(statusText(primary))
            builder.setProgress(100, (state.overallProgress * 100).toInt(), state.running.isEmpty())
            val style = NotificationCompat.InboxStyle()
            active.take(5).forEach { style.addLine("${it.title}: ${statusText(it)}") }
            builder.setStyle(style)
            addGroupActions(builder)
            builder.setGroupSummary(true)
        } else {
            builder.setContentTitle(primary.title)
            builder.setContentText(statusText(primary))
            builder.setProgress(
                100,
                (primary.progress * 100).toInt(),
                primary.status == DownloadStatus.WAITING_NETWORK ||
                    primary.status == DownloadStatus.QUEUED,
            )
            addItemActions(builder, primary)
        }
        return builder.build()
    }

    private suspend fun itemNotification(item: DownloadItemState): Notification {
        val builder = baseBuilder()
            .setGroup(DownloadNotifications.GROUP_KEY)
            .setContentTitle(item.title)
            .setContentText(statusText(item))
            .setProgress(
                100,
                (item.progress * 100).toInt(),
                item.status == DownloadStatus.WAITING_NETWORK || item.status == DownloadStatus.QUEUED,
            )
        addItemActions(builder, item)
        return builder.build()
    }

    private suspend fun completionNotification(state: DownloadsState): Notification? {
        val failed = state.items.count { it.status == DownloadStatus.FAILED }
        val done = state.items.firstOrNull { it.status == DownloadStatus.DONE }
        return when {
            failed > 0 -> baseBuilder()
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentTitle(text(Res.string.download_failed_title))
                .setContentText(failureText(state.items.lastOrNull { it.status == DownloadStatus.FAILED }?.failure))
                .build()

            done != null -> baseBuilder()
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentTitle(text(Res.string.download_complete_title))
                .setContentText(text(Res.string.download_complete_text, done.title))
                .build()

            else -> null
        }
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(getColor(R.color.glosso_color_notification))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private suspend fun addItemActions(builder: NotificationCompat.Builder, item: DownloadItemState) {
        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                builder.addAction(0, text(Res.string.download_pause), serviceAction(DownloadNotifications.ACTION_PAUSE, item.id, 1))

            DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                builder.addAction(0, text(Res.string.download_resume), serviceAction(DownloadNotifications.ACTION_RESUME, item.id, 1))

            DownloadStatus.WAITING_NETWORK ->
                builder.addAction(0, text(Res.string.download_allow_metered), serviceAction(DownloadNotifications.ACTION_ALLOW_METERED, item.id, 1))

            else -> Unit
        }
        builder.addAction(0, text(Res.string.download_cancel), serviceAction(DownloadNotifications.ACTION_CANCEL, item.id, 2))
    }

    private suspend fun addGroupActions(builder: NotificationCompat.Builder) {
        builder.addAction(0, text(Res.string.download_pause_all), serviceAction(DownloadNotifications.ACTION_PAUSE, null, 1))
        builder.addAction(0, text(Res.string.download_resume_all), serviceAction(DownloadNotifications.ACTION_RESUME, null, 2))
        builder.addAction(0, text(Res.string.download_cancel_all), serviceAction(DownloadNotifications.ACTION_CANCEL, null, 3))
    }

    private suspend fun statusText(item: DownloadItemState): String = when (item.status) {
        DownloadStatus.WAITING_NETWORK -> text(Res.string.download_waiting_wifi)
        DownloadStatus.PAUSED -> text(Res.string.download_paused)
        DownloadStatus.QUEUED -> text(Res.string.download_queued)
        DownloadStatus.FAILED -> failureText(item.failure)
        DownloadStatus.DONE -> text(Res.string.download_complete_title)
        DownloadStatus.CANCELLED -> text(Res.string.download_cancelled)
        DownloadStatus.RUNNING -> {
            val pct = (item.progress * 100).toInt()
            val total = item.bytesTotal
            if (total != null && total > 0) {
                text(Res.string.download_progress_bytes, formatBytes(item.bytesWritten), formatBytes(total)) +
                    " · " + text(Res.string.download_percent, pct)
            } else {
                formatBytes(item.bytesWritten) + " · " + text(Res.string.download_percent, pct)
            }
        }
    }

    private suspend fun failureText(failure: DownloadFailure?): String =
        text((failure ?: DownloadFailure.GENERIC).message)

    private fun serviceAction(actionName: String, itemId: String?, order: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(actionName)
        itemId?.let { intent.putExtra(DownloadNotifications.EXTRA_ITEM_ID, it) }
        return PendingIntent.getService(
            this,
            (actionName + (itemId ?: "all")).hashCode() + order,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, DownloadNotifications.FOREGROUND_ID, notification, type)
    }
}
