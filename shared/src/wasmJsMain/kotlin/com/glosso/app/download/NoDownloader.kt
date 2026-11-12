package com.glosso.app.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The web build's [Downloader]: it offers no offline downloads (AppConfig.offlineDownloads). */
internal object NoDownloader : Downloader {
    override val state: StateFlow<DownloadsState> = MutableStateFlow(DownloadsState())

    override fun enqueue(item: DownloadItem): Deferred<DownloadOutcome> = CompletableDeferred(DownloadOutcome.FAILED)
    override fun pause(id: String) = Unit
    override fun resume(id: String) = Unit
    override fun cancel(id: String) = Unit
    override fun allowMetered(id: String) = Unit
    override fun wifiOnlyChanged() = Unit
}
