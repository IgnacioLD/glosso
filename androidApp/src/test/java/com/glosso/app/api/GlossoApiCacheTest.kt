package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Path.Companion.toOkioPath
import okio.FileSystem

class GlossoApiCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class VersionStore(initial: String? = null) : AssetVersionStore {
        var value: String? = initial
        override fun load(): String? = value
        override fun save(version: String) {
            value = version
        }
    }

    @Test
    fun `content is served from the cache on the second call`() = runTest {
        var version = "v1"
        var storyRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> respond(manifest(version), headers = cacheable())
                "/v1/content/STORY" -> {
                    storyRequests++
                    respond(stories(version), headers = cacheable())
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

        api.manifest()
        assertEquals("v1", api.stories().first().title)
        assertEquals("v1", api.stories().first().title)

        assertEquals("the second story call never hit the network", 1, storyRequests)
    }

    @Test
    fun `a new asset version neither serves nor keeps the old content`() = runTest {
        var version = "v1"
        var storyRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> respond(manifest(version), headers = cacheable())
                "/v1/content/STORY" -> {
                    storyRequests++
                    respond(stories(version), headers = cacheable())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val store = VersionStore()
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = LruCacheStorage(folder.root.toOkioPath(), FileSystem.SYSTEM),
            versionStore = store,
        )

        api.manifest()
        assertEquals("v1", api.stories().first().title)

        version = "v2"
        api.manifest()
        val afterPublish = api.stories().first().title

        assertEquals("the new version is fetched, not the cached old one", "v2", afterPublish)
        assertEquals("v2", store.value)
        assertEquals(2, storyRequests)
        assertEquals(0, folder.root.listFiles().orEmpty().count { it.name.endsWith(".part") })
    }

    @Test
    fun `content requests carry the content version`() = runTest {
        var seenVersion: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> respond("""{"assetVersion":"v9"}""", headers = cacheable())
                "/v1/content/DRILL" -> {
                    seenVersion = request.url.parameters["v"]
                    respond("[]", headers = cacheable())
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

        api.manifest()
        api.drills()

        assertEquals("v9", seenVersion)
    }

    @Test
    fun `asset urls carry the content version for png and svg`() = runTest {
        var version = "v1"
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> respond(manifest(version), headers = cacheable())
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = LruCacheStorage(folder.root.toOkioPath(), FileSystem.SYSTEM),
            versionStore = VersionStore(),
        )

        api.manifest()
        val pngV1 = api.assetUrl("images/en.story.family.001.png")
        val svgV1 = api.assetUrl("images/en.story.family.001.svg")
        assertEquals("http://test/assets/content/en/images/en.story.family.001.png?v=v1", pngV1)
        assertEquals("http://test/assets/content/en/images/en.story.family.001.svg?v=v1", svgV1)

        version = "v2"
        api.manifest()
        val pngV2 = api.assetUrl("images/en.story.family.001.png")
        assertEquals("http://test/assets/content/en/images/en.story.family.001.png?v=v2", pngV2)
        assertNotEquals("a new version is a new URL", pngV1, pngV2)
        assertTrue("svg follows the same version", api.assetUrl("images/x.svg").endsWith("?v=v2"))
    }

    @Test
    fun `a content version change is announced for the offline store`() = runTest {
        var version = "v1"
        var announced: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> respond(manifest(version), headers = cacheable())
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = LruCacheStorage(folder.root.toOkioPath(), FileSystem.SYSTEM),
            versionStore = VersionStore(),
        )
        api.onContentVersionChanged = { announced = it }

        api.manifest()
        assertEquals("the first version is announced", "v1", announced)

        version = "v2"
        api.manifest()
        assertEquals("a change is announced", "v2", announced)
    }

    @Test
    fun `ensureAssetVersion refreshes a stale persisted version`() = runTest {
        var version = "v1"
        var manifestRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/content/manifest" -> {
                    manifestRequests++
                    respond(manifest(version), headers = cacheable())
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val store = VersionStore("v0")
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = LruCacheStorage(folder.root.toOkioPath(), FileSystem.SYSTEM),
            versionStore = store,
        )
        assertTrue("the persisted version is used at once", api.assetUrl("audio/x.ogg").endsWith("?v=v0"))

        api.ensureAssetVersion()

        assertEquals("the served version replaces the persisted one", "v1", store.value)
        assertEquals(1, manifestRequests)
        assertTrue("asset urls move to the served version", api.assetUrl("audio/x.ogg").endsWith("?v=v1"))
    }

    @Test
    fun `score is never cached`() = runTest {
        var scoreRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/score" -> {
                    scoreRequests++
                    respond("""{"score":90}""", headers = cacheable())
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

        val first = api.score(ScoreRequest("hello"))
        val second = api.score(ScoreRequest("hello"))

        assertEquals(90, first.score)
        assertEquals(90, second.score)
        assertEquals("a mutable POST is always sent", 2, scoreRequests)
    }

    @Test
    fun `downloadTo streams to disk without asking the http cache to buffer it`() = runTest {
        // Regression guard: a cacheable response is read fully into memory by the
        // HttpCache plugin before it is stored, which OOMed on the ~600 MB model.
        // downloadTo must opt out of storage for the body to stay streaming.
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        var cacheControl: String? = null
        val engine = MockEngine { request ->
            cacheControl = request.headers[HttpHeaders.CacheControl]
            respond(
                payload,
                headers = Headers.build {
                    append(HttpHeaders.ContentLength, payload.size.toString())
                    append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                },
            )
        }
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = LruCacheStorage(folder.root.toOkioPath(), FileSystem.SYSTEM),
            versionStore = VersionStore(),
        )
        val target = java.io.File(folder.root, "model.bin")

        api.downloadTo("http://test/assets/model.bin", target)

        assertEquals("no-store", cacheControl)
        assertEquals(payload.size.toLong(), target.length())
    }

    private fun cacheable(): Headers = Headers.build {
        append(HttpHeaders.ContentType, "application/json")
        append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    }


    private fun manifest(version: String) = """{"assetVersion":"$version"}"""

    private fun stories(version: String) = """[{"id":"$version","title":"$version"}]"""
}
