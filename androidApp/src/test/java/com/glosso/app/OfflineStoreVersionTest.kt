package com.glosso.app

import android.app.Application
import com.glosso.app.download.DownloadManager
import com.glosso.app.api.AssetVersionStore
import com.glosso.app.api.GlossoApi
import com.glosso.app.api.LruCacheStorage
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.WordModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okio.Path.Companion.toOkioPath
import okio.FileSystem

/**
 * The bug this guards: the app resolved a downloaded asset first and kept it
 * forever, so a rebuilt catalogue never reached the learner. A pack downloaded
 * for one content version must stop being served once the version changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineStoreVersionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class VersionStore : AssetVersionStore {
        override fun load(): String? = null
        override fun save(version: String) = Unit
    }

    private fun bundle() = OfflineBundle(
        lesson = LessonModel(id = "l1", title = "Lesson", theme = "city", level = "A1"),
        words = listOf(
            WordModel(
                id = "w1",
                lemma = "city",
                audio = mapOf("en-GB-female" to "audio/city.ogg"),
            ),
        ),
    )

    @Test
    fun `a content version change invalidates the older pack and re-downloads`() = runTest {
        OfflineStore.downloader = DownloadManager
        OfflineStore.init(RuntimeEnvironment.getApplication())
        OfflineStore.clear()

        var version = "v1"
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/content/manifest" ->
                    respond(
                        """{"assetVersion":"$version"}""",
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/json")
                            append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                        },
                    )
                request.url.encodedPath.endsWith("/audio/city.ogg") -> {
                    val bytes = byteArrayOf(1, 2, 3)
                    respond(
                        bytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, "application/octet-stream")
                            // Real servers always send this; the downloader refuses
                            // a body it cannot verify otherwise.
                            append(HttpHeaders.ContentLength, bytes.size.toString())
                        },
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = LruCacheStorage(folder.root.toOkioPath(), FileSystem.SYSTEM),
            versionStore = VersionStore(),
        )
        api.onContentVersionChanged = { OfflineStore.setContentVersion(it) }

        api.manifest()
        assertEquals("v1", api.contentVersion)
        assertTrue(OfflineStore.download(bundle(), api))
        assertNotNull("the v1 pack is on disk", OfflineStore.localAsset("audio/city.ogg"))

        version = "v2"
        api.manifest()
        assertNull("an older pack is never served", OfflineStore.localAsset("audio/city.ogg"))

        assertTrue("the pack re-downloads for the new version", OfflineStore.download(bundle(), api))
        assertNotNull("the v2 pack is on disk", OfflineStore.localAsset("audio/city.ogg"))
    }
}
