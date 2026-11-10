package com.glosso.app

import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore

/**
 * Whether the notification permission was already asked for around a download:
 * it is asked once and only where it is relevant. The permission itself is read
 * through `rememberPermissionRequest(AppPermission.NOTIFICATIONS)`.
 */
object DownloadPermission {

    private const val FILE = "glosso_downloads"
    private const val KEY_ASKED = "notification_prompted"

    fun wasAsked(context: PlatformContext): Boolean = keyValueStore(context, FILE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: PlatformContext) {
        keyValueStore(context, FILE).edit { putBoolean(KEY_ASKED, true) }
    }
}
