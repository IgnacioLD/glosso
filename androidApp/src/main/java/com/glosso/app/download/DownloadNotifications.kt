package com.glosso.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.glosso.app.R

/**
 * The download notification's fixed names: channel, group and the action verbs
 * the service reacts to. Kept in one place so the service and the builders agree.
 */
object DownloadNotifications {

    const val CHANNEL_ID = "glosso_downloads"
    const val GROUP_KEY = "glosso.downloads"

    /** The ongoing foreground notification (the group summary when several run). */
    const val FOREGROUND_ID = 0x10DA

    /** The short, dismissable "done" note. */
    const val COMPLETE_ID = 0x10DB

    const val ACTION_PAUSE = "com.glosso.app.download.action.PAUSE"
    const val ACTION_RESUME = "com.glosso.app.download.action.RESUME"
    const val ACTION_CANCEL = "com.glosso.app.download.action.CANCEL"
    const val ACTION_ALLOW_METERED = "com.glosso.app.download.action.ALLOW_METERED"

    const val EXTRA_ITEM_ID = "com.glosso.app.download.extra.ITEM_ID"

    /** Stable per-item notification id, so updates land on the same row. */
    fun itemNotificationId(id: String): Int = id.hashCode() and 0x00FFFFFF

    /**
     * A quiet, ongoing channel: downloads are useful, not an event. No sound and
     * low importance, so the bar and the actions appear without ever buzzing.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
