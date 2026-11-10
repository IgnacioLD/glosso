package com.glosso.app

import android.app.Application
import com.glosso.app.api.ApiCache
import com.glosso.app.api.GlossoApi
import com.glosso.app.download.DownloadItem
import com.glosso.app.download.DownloadItemState
import com.glosso.app.download.DownloadKind
import com.glosso.app.download.DownloadManager
import com.glosso.app.download.DownloadOutcome
import com.glosso.app.download.DownloadStatus
import com.glosso.app.download.Downloader
import com.glosso.app.download.DownloadsState
import com.glosso.app.platform.keyValueStore
import com.glosso.app.product.ProductConfigRepository
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.WordModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The server setting: an address is used only once it answers like a Glosso
 * server, and switching drops everything learned from the previous server so
 * nothing from A is used against B.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerSwitchTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()

    private val engine = MockEngine { request ->
        val host = request.url.host
        requests += request.url.toString()
        val tag = host.substringBefore('.')
        when {
            host == "down.test" -> throw IOException("connection refused")
            host == "web.test" -> respond("<html>hello</html>", HttpStatusCode.OK)
            request.url.encodedPath == "/health" ->
                respond("""{"status":"ok","recognition":true,"assets":"ready","analytics":false}""", headers = json)
            request.url.encodedPath == "/v1/config" -> respond("""{"schema":1,"flags":{"server_$tag":true}}""", headers = json)
            request.url.encodedPath == "/v1/me" ->
                respond(if (tag == "a") """{"linkedAccounts":["google"]}""" else "{}", headers = json)
            // mirror.test serves A's content (same fingerprint); nofp.test publishes none.
            request.url.encodedPath == "/v1/content/manifest" -> when (tag) {
                "mirror" -> respond("""{"assetVersion":"a-v1"}""", headers = json)
                "nofp" -> respond("{}", headers = json)
                else -> respond("""{"assetVersion":"$tag-v1"}""", headers = json)
            }
            request.url.encodedPath.endsWith("/audio/city.ogg") ->
                respond(byteArrayOf(1, 2, 3), headers = headersOf(HttpHeaders.ContentLength, "3"))
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private class FakeDownloader(active: List<DownloadItemState>) : Downloader {
        val cancelled = mutableListOf<String>()
        override val state: StateFlow<DownloadsState> = MutableStateFlow(DownloadsState(active))
        override fun enqueue(item: DownloadItem): Deferred<DownloadOutcome> = error("not used")
        override fun pause(id: String) = Unit
        override fun resume(id: String) = Unit
        override fun cancel(id: String) { cancelled += id }
        override fun allowMetered(id: String) = Unit
        override fun wifiOnlyChanged() = Unit
    }

    private fun bundle() = OfflineBundle(
        lesson = LessonModel(id = "l1", title = "Lesson", theme = "city", level = "A1"),
        words = listOf(WordModel(id = "w1", lemma = "city", audio = mapOf("en-GB-female" to "audio/city.ogg"))),
    )

    /** Downloads a pack from A, switches to [server] and answers whether the pack survived. */
    private suspend fun packSurvivesSwitchTo(server: String): Boolean {
        val app = RuntimeEnvironment.getApplication()
        val serverStore = keyValueStore(app, "glosso_server").apply { edit(commit = true) { clear() } }
        ServerPreferences.init(serverStore, "https://a.test")
        ApiCache.init(app)
        ApiCache.forget()
        OfflineStore.downloader = DownloadManager
        OfflineStore.init(app)
        OfflineStore.clear()
        val api = GlossoApi(engine = engine, contentCache = null, versionStore = ApiCache)
        api.onContentVersionChanged = { OfflineStore.setContentVersion(it) }
        ProductConfigRepository.api = { api }
        api.manifest()
        OfflineStore.setContentVersion(api.contentVersion)
        assertTrue(OfflineStore.download(bundle(), api))
        assertNotNull("the pack is on disk before the switch", OfflineStore.localAsset("audio/city.ogg"))

        ServerSwitch.apply(server, FakeDownloader(emptyList()), NoOnDeviceScoring, api)
        val kept = OfflineStore.bundles().map { it.lesson.id } == listOf("l1")
        assertEquals(kept, OfflineStore.localAsset("audio/city.ogg") != null)
        return kept
    }

    @Test
    fun `a pack is kept when the new server publishes the same fingerprint`() = runTest {
        assertTrue(packSurvivesSwitchTo("https://mirror.test"))
    }

    @Test
    fun `a pack is deleted when the fingerprint differs, is missing or cannot be fetched`() = runTest {
        assertFalse("different fingerprint", packSurvivesSwitchTo("https://b.test"))
        assertFalse("no fingerprint", packSurvivesSwitchTo("https://nofp.test"))
        assertFalse("unreachable", packSurvivesSwitchTo("https://down.test"))
    }

    @Test
    fun `the probe accepts only a Glosso server`() = runTest {
        assertEquals(ServerProbe.OK, probeServer("https://b.test", engine))
        assertEquals(ServerProbe.NOT_GLOSSO, probeServer("https://web.test", engine))
        assertEquals(ServerProbe.NOT_GLOSSO, probeServer("https://b.test/not-here", engine))
        assertEquals(ServerProbe.UNREACHABLE, probeServer("https://down.test", engine))
    }

    @Test
    fun `switching server drops what belonged to the previous one`() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val serverStore = keyValueStore(app, "glosso_server").apply { edit(commit = true) { clear() } }
        ServerPreferences.init(serverStore, "https://a.test")
        ApiCache.init(app)
        val downloader = FakeDownloader(
            listOf(
                DownloadItemState("lesson:l1", DownloadKind.LESSON, "L1", DownloadStatus.RUNNING),
                DownloadItemState("lesson:l0", DownloadKind.LESSON, "L0", DownloadStatus.DONE),
            ),
        )
        OfflineStore.downloader = downloader
        OfflineStore.init(app)
        GoogleAccountStore.init(app)
        GoogleAccountStore.signedIn("learner@example.com")

        // Built before the switch and never rebuilt, like every ViewModel's client.
        val api = GlossoApi(engine = engine, contentCache = null, versionStore = ApiCache)
        api.onContentVersionChanged = { OfflineStore.setContentVersion(it) }
        ProductConfigRepository.api = { api }
        api.manifest()
        ProductConfigRepository.refresh()
        assertEquals("a-v1", ApiCache.load())
        assertTrue(ProductConfigRepository.config.value.flag("server_a"))
        assertEquals("learner@example.com", GoogleAccountStore.email.value)

        requests.clear()
        ServerSwitch.apply("https://b.test", downloader, NoOnDeviceScoring, api)

        assertEquals("https://b.test", ServerPreferences.base.value)
        assertEquals(listOf("lesson:l1"), downloader.cancelled)
        assertNull("an account linked on A is not linked on B", GoogleAccountStore.email.value)
        assertFalse(ProductConfigRepository.config.value.flag("server_a"))
        assertTrue(ProductConfigRepository.config.value.flag("server_b"))
        assertEquals("b-v1", ApiCache.load())
        assertEquals("b-v1", api.contentVersion)
        assertTrue(requests.isNotEmpty())
        assertTrue("every request goes to B: $requests", requests.all { it.startsWith("https://b.test/") })
        assertTrue("no request carries A's version: $requests", requests.none { "a-v1" in it })
        assertEquals("https://b.test/assets/content/en/audio/x.ogg?v=b-v1", api.assetUrl("audio/x.ogg"))
        assertEquals(emptyList<Any>(), OfflineStore.bundles())

        requests.clear()
        ServerSwitch.apply(null, downloader, NoOnDeviceScoring, api)
        assertEquals("https://a.test", ServerPreferences.base.value)
        assertFalse(ServerPreferences.isCustom)
        assertTrue(ProductConfigRepository.config.value.flag("server_a"))
        assertEquals("a-v1", api.contentVersion)
        assertTrue(requests.all { it.startsWith("https://a.test/") })
    }
}
