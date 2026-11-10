package com.glosso.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The offline drawer paints the model on its own card, so the shared list it
 * renders below must exclude the model; otherwise one download shows two bars
 * and two cancel buttons.
 */
class DownloadsStateTest {

    private fun item(id: String, kind: DownloadKind, status: DownloadStatus) =
        DownloadItemState(id = id, kind = kind, title = id, status = status)

    @Test
    fun `the model is not listed among the active downloads`() {
        val state = DownloadsState(
            listOf(
                item(DownloadManager.modelItemId(), DownloadKind.MODEL, DownloadStatus.RUNNING),
                item("lesson:a", DownloadKind.LESSON, DownloadStatus.RUNNING),
                item("lesson:b", DownloadKind.LESSON, DownloadStatus.DONE),
            ),
        )

        assertEquals(listOf("lesson:a"), state.activeLessons.map { it.id })
    }

    @Test
    fun `a finished model does not leak into the active downloads either`() {
        val state = DownloadsState(
            listOf(item(DownloadManager.modelItemId(), DownloadKind.MODEL, DownloadStatus.DONE)),
        )

        assertEquals(emptyList<DownloadItemState>(), state.activeLessons)
    }
}
