package com.glosso.app.download

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's one download queue as the shared code sees it: the model pack and
 * each lesson's files go through it, so there is one queue and one set of rules
 * (Wi-Fi only, pause, resume). Android implements it with `DownloadManager` and
 * its foreground service; iOS will with a background `URLSession` (phase 3).
 */
interface Downloader {
    val state: StateFlow<DownloadsState>

    /** Enqueues [item] (resuming a known one); the handle completes with its outcome. */
    fun enqueue(item: DownloadItem): Deferred<DownloadOutcome>

    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)

    /** The learner accepts a metered connection for this one item. */
    fun allowMetered(id: String)

    /** The "only on Wi-Fi" setting changed: re-check what may run on this network now. */
    fun wifiOnlyChanged()
}

/**
 * The app's [Downloader], provided by the host around the whole UI, so a screen
 * that pauses, resumes or cancels a download never reaches for a platform singleton.
 */
val LocalDownloader = staticCompositionLocalOf<Downloader> { error("LocalDownloader is not provided") }

/** The queue ids of the two kinds of download. */
object DownloadIds {
    const val MODEL = "model"
    private const val LESSON_PREFIX = "lesson:"

    fun lesson(lessonId: String): String = LESSON_PREFIX + lessonId

    fun lessonIdOf(itemId: String): String = itemId.removePrefix(LESSON_PREFIX)
}
