package com.glosso.app

import android.app.Application
import com.glosso.app.api.ApiCache
import com.glosso.app.api.GlossoApi
import com.glosso.app.download.DownloadItem
import com.glosso.app.download.DownloadItemState
import com.glosso.app.download.DownloadKind
import com.glosso.app.download.DownloadOutcome
import com.glosso.app.download.DownloadStatus
import com.glosso.app.download.Downloader
import com.glosso.app.download.DownloadsState
import com.glosso.app.platform.keyValueStore
import com.glosso.app.product.ProductConfigRepository
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
            request.url.encodedPath == "/v1/content/manifest" -> respond("""{"assetVersion":"$tag-v1"}""", headers = json)
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
