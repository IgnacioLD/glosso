package com.glosso.app.download

import com.glosso.app.OfflineStore
import com.glosso.app.platform.Connectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem

/**
 * The iOS [Downloader] until phase 3: the shared [DownloadEngine] run in the
 * app's process, so lesson packs download while the app is open, with the same
 * queue and Wi-Fi rule as Android. There is no model pack on iOS yet
 * (`NoOnDeviceScoring`), so only lessons go through it.
 *
 * TODO(ios phase 3): a background `URLSession` so a download survives leaving
 * the app, and the files excluded from the iCloud backup.
 */
internal class ForegroundDownloader(private val connectivity: Connectivity) : Downloader {

    private val engine = DownloadEngine(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        fileSystem = FileSystem.SYSTEM,
        onChanged = { snapshot ->
            OfflineStore.onQueueState(snapshot.active.firstOrNull { it.kind == DownloadKind.LESSON })
        },
        onCompleted = { item ->
            if (item.kind == DownloadKind.LESSON) OfflineStore.commitDownload(DownloadIds.lessonIdOf(item.id))
        },
        onFailed = { item, failure ->
            if (item.kind == DownloadKind.LESSON) OfflineStore.onDownloadFailed(DownloadIds.lessonIdOf(item.id), failure)
        },
        networkAllowed = ::networkAllowed,
    )

    override val state: StateFlow<DownloadsState> get() = engine.state

    override fun enqueue(item: DownloadItem): Deferred<DownloadOutcome> = engine.enqueue(item)
    override fun pause(id: String) = engine.pause(id)
    override fun resume(id: String) = engine.resume(id)
    override fun cancel(id: String) = engine.cancel(id)
    override fun allowMetered(id: String) = engine.allowMetered(id)
    override fun wifiOnlyChanged() = engine.networkChanged()

    private fun networkAllowed(item: DownloadItem): Boolean {
        if (!connectivity.isOnline()) return false
        if (item.allowMetered || !WifiOnlyPreference.enabled.value) return true
        return connectivity.isUnmetered()
    }
}
