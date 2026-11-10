package com.glosso.app

import com.glosso.app.download.DownloadFailure
import com.glosso.app.download.DownloadIds
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

/** The on-disk layout of offline packs is fixed: earlier versions' downloads must be found again. */
class OfflineLayoutTest {

    private val layout = OfflineLayout("/data/user/0/me.shirobyte42.glosso/files".toPath())

    @Test
    fun `packs live under filesDir offline`() {
        assertEquals("/data/user/0/me.shirobyte42.glosso/files/offline/index.json", layout.index.toString())
        assertEquals("/data/user/0/me.shirobyte42.glosso/files/offline/lessons/l1.json", layout.bundle("l1").toString())
        assertEquals("/data/user/0/me.shirobyte42.glosso/files/offline/assets/v1", layout.assetsFor("v1").toString())
        assertEquals(
            "/data/user/0/me.shirobyte42.glosso/files/offline/assets/v1/audio/a.ogg",
            layout.asset("v1", "audio/a.ogg").toString(),
        )
        assertEquals("/data/user/0/me.shirobyte42.glosso/files/offline/pending/l1.json", layout.pendingBundle("l1").toString())
        assertEquals("/data/user/0/me.shirobyte42.glosso/files/offline/pending/l1.version", layout.pendingVersion("l1").toString())
    }

    @Test
    fun `queue ids keep their shape`() {
        assertEquals("model", DownloadIds.MODEL)
        assertEquals("lesson:l1", DownloadIds.lesson("l1"))
        assertEquals("l1", DownloadIds.lessonIdOf("lesson:l1"))
    }

    @Test
    fun `transport errors map to the failure the learner is told`() {
        assertEquals(DownloadFailure.DAMAGED, DownloadFailure.of("Checksum mismatch for x"))
        assertEquals(DownloadFailure.DAMAGED, DownloadFailure.of("Incomplete download: 1 of 2 bytes"))
        assertEquals(DownloadFailure.REJECTED, DownloadFailure.of("Download failed: HTTP 404 for x"))
        assertEquals(DownloadFailure.SERVER, DownloadFailure.of("Download failed: HTTP 503 for x"))
        assertEquals(DownloadFailure.SPACE, DownloadFailure.of("write failed: ENOSPC (No space left on device)"))
        assertEquals(DownloadFailure.NETWORK, DownloadFailure.of("java.net.UnknownHostException: api"))
        assertEquals(DownloadFailure.NETWORK, DownloadFailure.of("Socket timeout has expired"))
        assertEquals(DownloadFailure.GENERIC, DownloadFailure.of("boom"))
    }
}
