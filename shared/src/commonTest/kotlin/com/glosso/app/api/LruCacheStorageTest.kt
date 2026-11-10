package com.glosso.app.api

import com.glosso.app.platform.currentTimeMillis
import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path

class LruCacheStorageTest {

    private val fileSystem = FileSystem.SYSTEM
    private val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "lru-test-${Random.nextLong().toULong()}"

    @AfterTest
    fun cleanUp() = fileSystem.deleteRecursively(root)

    private fun folder(name: String): Path = (root / name).also { fileSystem.createDirectories(it) }

    @Test
    fun `evicts the least recently used entry once over the size limit`() = runTest {
        val dir = folder("cache")
        val storage = LruCacheStorage(dir, fileSystem, maxBytes = capForTwoEntries())
        val first = Url("http://test/first")
        val second = Url("http://test/second")
        val third = Url("http://test/third")

        storage.store(first, data(first))
        storage.store(second, data(second))
        assertEquals(2, cacheFiles(dir), "both fit")

        storage.store(third, data(third))

        assertNull(storage.find(first, emptyMap()), "the least recently used is evicted")
        assertNotNull(storage.find(second, emptyMap()))
        assertNotNull(storage.find(third, emptyMap()))
        assertEquals(2, cacheFiles(dir), "only the capped number of files remains")
    }

    @Test
    fun `reading an entry makes it the most recently used`() = runTest {
        val dir = folder("cache")
        val storage = LruCacheStorage(dir, fileSystem, maxBytes = capForTwoEntries())
        val first = Url("http://test/first")
        val second = Url("http://test/second")
        val third = Url("http://test/third")

        storage.store(first, data(first))
        storage.store(second, data(second))
        // Touch the oldest so it should survive the next eviction.
        assertNotNull(storage.find(first, emptyMap()))
        storage.store(third, data(third))

        assertNotNull(storage.find(first, emptyMap()), "recently read entry survives")
        assertNull(storage.find(second, emptyMap()), "now the second is the oldest")
    }

    @Test
    fun `clear drops every entry`() = runTest {
        val dir = folder("cache")
        val storage = LruCacheStorage(dir, fileSystem, maxBytes = capForTwoEntries())
        val first = Url("http://test/first")
        storage.store(first, data(first))
        assertNotNull(storage.find(first, emptyMap()))

        storage.clear()

        assertNull(storage.find(first, emptyMap()))
        assertEquals(0, cacheFiles(dir))
    }

    @Test
    fun `entries keep the file names earlier versions wrote`() = runTest {
        // SHA-256 of the URL in lowercase hex, as the JVM-only version named them,
        // so an update finds the cache already on disk.
        val dir = folder("cache")
        val url = Url("http://test/first")
        LruCacheStorage(dir, fileSystem).store(url, data(url))
        assertEquals(
            listOf("4e2956df879e19b1aadd357e5d9554cac87ca9f2bb37ea67ab38ae300170b397.cache"),
            fileSystem.list(dir).map { it.name },
        )
    }

    @Test
    fun `a new process finds the entries on disk`() = runTest {
        val dir = folder("cache")
        val url = Url("http://test/first")
        LruCacheStorage(dir, fileSystem).store(url, data(url))

        val reopened = LruCacheStorage(dir, fileSystem).find(url, emptyMap())

        assertEquals(200, reopened?.body?.size)
        assertEquals("public, max-age=3600", reopened?.headers?.get("Cache-Control"))
    }

    /** Two and a half times one stored entry: two entries fit, three cannot. */
    private suspend fun capForTwoEntries(): Long {
        val scratch = folder("measure")
        val probe = LruCacheStorage(scratch, fileSystem, maxBytes = Long.MAX_VALUE)
        val url = Url("http://test/measure")
        probe.store(url, data(url))
        val unit = fileSystem.list(scratch).maxOf { fileSystem.metadata(it).size ?: 0L }
        return unit * 2 + unit / 2
    }

    private fun cacheFiles(dir: Path): Int = fileSystem.list(dir).count { it.name.endsWith(".cache") }

    private fun data(url: Url) = CachedResponseData(
        url = url,
        statusCode = HttpStatusCode.OK,
        requestTime = GMTDate(0),
        responseTime = GMTDate(0),
        version = HttpProtocolVersion.HTTP_1_1,
        expires = GMTDate(currentTimeMillis() + 3_600_000),
        headers = Headers.build { append("Cache-Control", "public, max-age=3600") },
        varyKeys = emptyMap(),
        body = ByteArray(200) { 1 },
    )
}
