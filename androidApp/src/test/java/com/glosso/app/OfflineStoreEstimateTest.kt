package com.glosso.app

import android.app.Application
import com.glosso.app.api.AssetVersionStore
import com.glosso.app.api.GlossoApi
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.WordModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Sizing a lesson must not be one network round trip per asset. When the server
 * publishes sizes in the manifest, the whole lesson is a local sum; otherwise it
 * probes once and remembers. Either way, opening the sheet again is free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineStoreEstimateTest {

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

    private fun twoAssetBundle() = OfflineBundle(
        lesson = LessonModel(id = "l2", title = "Lesson", theme = "city", level = "A1"),
        words = listOf(
            WordModel(id = "w1", lemma = "a", audio = mapOf("en-GB-female" to "audio/a.ogg")),
            WordModel(id = "w2", lemma = "b", audio = mapOf("en-GB-female" to "audio/b.ogg")),
        ),
    )

    private val jsonHeaders = Headers.build {
        append(HttpHeaders.ContentType, "application/json")
    }

    private fun bytesHeaders(size: Int) = Headers.build {
        append(HttpHeaders.ContentType, "application/octet-stream")
        append(HttpHeaders.ContentLength, size.toString())
    }

    @Test
    fun `sizes come from the manifest with no per-asset requests`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> respond(
                    """{"assetVersion":"v1","assets":{"audio/city.ogg":123}}""",
                    headers = jsonHeaders,
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        OfflineStore.init(RuntimeEnvironment.getApplication())
        OfflineStore.clear()
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null, versionStore = VersionStore())

        val bytes = OfflineStore.estimate(bundle(), api)

        assertEquals(123L, bytes)
        println("REQ1=" + engine.requestHistory.map { it.url.toString() })
        assertEquals(
            "only the manifest is fetched",
            listOf("/v1/content/manifest"),
            engine.requestHistory.map { it.url.encodedPath },
        )

        val requestsAfterFirst = engine.requestHistory.size
        assertEquals("the second call is cached", 123L, OfflineStore.estimate(bundle(), api))
        assertEquals(requestsAfterFirst, engine.requestHistory.size)
    }

    @Test
    fun `without published sizes each asset is probed once and cached`() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/content/manifest" ->
                    respond("""{"assetVersion":"v1"}""", headers = jsonHeaders)
                else -> respond(
                    ByteArray(42),
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentLength, "42")
                    },
                )
            }
        }
        OfflineStore.init(RuntimeEnvironment.getApplication())
        OfflineStore.clear()
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null, versionStore = VersionStore())

        assertEquals(42L, OfflineStore.estimate(bundle(), api))

        val requested = engine.requestHistory.map { it.url.encodedPath }
        val assetRequests = requested.count { it.contains("/assets/") }
        assertEquals("one probe for the one asset", 1, assetRequests)

        val requestsAfterFirst = engine.requestHistory.size
        assertEquals(42L, OfflineStore.estimate(bundle(), api))
        assertEquals("the probe is not repeated", requestsAfterFirst, engine.requestHistory.size)
    }

    @Test
    fun `a manifest that misses one asset falls back to probing the rest`() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/content/manifest" ->
                    respond("""{"assetVersion":"v1","assets":{"audio/a.ogg":10}}""", headers = jsonHeaders)
                request.url.encodedPath.endsWith("/audio/a.ogg") ->
                    respond(ByteArray(10), headers = bytesHeaders(10))
                request.url.encodedPath.endsWith("/audio/b.ogg") ->
                    respond(ByteArray(20), headers = bytesHeaders(20))
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        OfflineStore.init(RuntimeEnvironment.getApplication())
        OfflineStore.clear()
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null, versionStore = VersionStore())

        // The manifest is all-or-nothing: one unpublished asset means the lesson
        // is not sized from it, so the assets are probed instead and the total is
        // still correct rather than missing.
        assertEquals(30L, OfflineStore.estimate(twoAssetBundle(), api))
        val assets = engine.requestHistory.map { it.url.encodedPath }.filter { it.contains("/assets/") }
        assertTrue("the unpublished asset was probed", assets.any { it.endsWith("/audio/b.ogg") })
    }

    @Test
    fun `a partial estimate is shown, not cached, and self-corrects`() = runTest {
        var bAttempts = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/content/manifest" ->
                    respond("""{"assetVersion":"v1"}""", headers = jsonHeaders)

                request.url.encodedPath.endsWith("/audio/a.ogg") ->
                    respond(ByteArray(10), headers = bytesHeaders(10))

                request.url.encodedPath.endsWith("/audio/b.ogg") -> {
                    bAttempts++
                    if (bAttempts == 1) {
                        respond("boom", HttpStatusCode.InternalServerError)
                    } else {
                        respond(ByteArray(20), headers = bytesHeaders(20))
                    }
                }

                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        OfflineStore.init(RuntimeEnvironment.getApplication())
        OfflineStore.clear()
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null, versionStore = VersionStore())

        // One probe failed: the resolved asset is still shown, not hidden.
        assertEquals(10L, OfflineStore.estimate(twoAssetBundle(), api))

        // It was not cached, so the failed probe is retried and the total is
        // corrected instead of sticking at the undercount.
        assertEquals(30L, OfflineStore.estimate(twoAssetBundle(), api))

        val requestsAfterSecond = engine.requestHistory.size
        assertEquals("the completed estimate is cached", 30L, OfflineStore.estimate(twoAssetBundle(), api))
        assertEquals(requestsAfterSecond, engine.requestHistory.size)
        assertEquals("the failed probe was retried exactly once", 2, bAttempts)
    }
}
