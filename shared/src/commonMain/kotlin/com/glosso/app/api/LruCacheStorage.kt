package com.glosso.app.api

import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import com.glosso.app.platform.currentTimeMillis
import com.glosso.app.platform.setLastModified
import io.ktor.util.date.GMTDate
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

/**
 * Disk cache for the Ktor `HttpCache` plugin with a bounded size and LRU
 * eviction: one JSON file per URL, an in-memory index of file sizes ordered by
 * last use, and the least recently used files deleted once the total passes
 * [maxBytes]. Content is immutable per version, so the index only has to keep
 * disk usage in check, not decide freshness - HTTP headers do that.
 */
class LruCacheStorage(
    private val directory: Path,
    private val fileSystem: FileSystem,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ContentCacheStorage {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /**
     * File name to size, in access order: the eldest entry is evicted first. Kept
     * in order by re-inserting an entry on every use ([use]).
     */
    private val index = LinkedHashMap<String, Long>()

    @Volatile
    private var indexed = false

    override suspend fun store(url: Url, data: CachedResponseData) {
        val name = fileName(url)
        mutex.withLock {
            ensureIndexedLocked()
            withContext(Dispatchers.IO) {
                fileSystem.createDirectories(directory)
                val variants = readVariantsLocked(name)
                    .filterNot { it.varyKeys == data.varyKeys }
                    .plus(StoredVariant.from(data))
                writeLocked(name, variants)
                use(name, sizeOf(fileFor(name)))
            }
            evictLocked()
        }
    }

    override suspend fun find(url: Url, varyKeys: Map<String, String>): CachedResponseData? {
        val name = fileName(url)
        mutex.withLock {
            ensureIndexedLocked()
            touchLocked(name)
            return readVariantsLocked(name).firstOrNull { stored ->
                varyKeys.all { (key, value) -> stored.varyKeys[key] == value }
            }?.toData()
        }
    }

    override suspend fun findAll(url: Url): Set<CachedResponseData> {
        val name = fileName(url)
        mutex.withLock {
            ensureIndexedLocked()
            touchLocked(name)
            return readVariantsLocked(name).map { it.toData() }.toSet()
        }
    }

    /** Drops every entry, e.g. when the served content version changes. */
    override suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                fileSystem.listOrNull(directory)?.forEach { runCatching { fileSystem.delete(it) } }
            }
            index.clear()
            indexed = true
        }
    }

    private fun ensureIndexedLocked() {
        if (indexed) return
        index.clear()
        val files = fileSystem.listOrNull(directory).orEmpty()
            .filter { it.name.endsWith(SUFFIX) }
            .mapNotNull { path ->
                fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.let { path to it }
            }
            .sortedBy { (_, metadata) -> metadata.lastModifiedAtMillis ?: 0L }
        files.forEach { (path, metadata) -> index[path.name] = metadata.size ?: 0L }
        indexed = true
    }

    private suspend fun readVariantsLocked(name: String): List<StoredVariant> =
        withContext(Dispatchers.IO) {
            val file = fileFor(name)
            if (fileSystem.metadataOrNull(file)?.isRegularFile != true) return@withContext emptyList()
            runCatching { json.decodeFromString<List<StoredVariant>>(fileSystem.read(file) { readUtf8() }) }
                .getOrElse { emptyList() }
        }

    private suspend fun writeLocked(name: String, variants: List<StoredVariant>) {
        withContext(Dispatchers.IO) {
            val file = fileFor(name)
            val partial = directory / (name + ".part")
            fileSystem.write(partial) { writeUtf8(json.encodeToString(variants)) }
            runCatching { fileSystem.atomicMove(partial, file) }.onFailure {
                fileSystem.copy(partial, file)
                fileSystem.delete(partial)
            }
        }
    }

    private fun touchLocked(name: String) {
        index[name]?.let { use(name, it) }
        setLastModified(fileFor(name), currentTimeMillis())
    }

    /** Records [name] as the most recently used entry. */
    private fun use(name: String, size: Long) {
        index.remove(name)
        index[name] = size
    }

    private fun sizeOf(path: Path): Long = fileSystem.metadataOrNull(path)?.size ?: 0L

    private suspend fun evictLocked() {
        var total = index.values.sum()
        val iterator = index.entries.iterator()
        while (total > maxBytes && iterator.hasNext()) {
            // Read the entry before removing it: on Kotlin/Native a removed
            // entry no longer reads (ConcurrentModificationException).
            val (name, size) = iterator.next().let { it.key to it.value }
            iterator.remove()
            total -= size
            withContext(Dispatchers.IO) { runCatching { fileSystem.delete(fileFor(name)) } }
        }
    }

    private fun fileFor(name: String): Path = directory / name

    /** The SHA-256 of the URL in lowercase hex, so a URL always maps to the same file. */
    private fun fileName(url: Url): String = url.toString().encodeUtf8().sha256().hex() + SUFFIX

    @Serializable
    private data class StoredVariant(
        val url: String,
        val status: Int,
        val requestTime: Long,
        val responseTime: Long,
        val version: String,
        val expires: Long,
        val headers: List<List<String>>,
        val varyKeys: Map<String, String>,
        val body: String,
    ) {
        fun toData() = CachedResponseData(
            url = Url(url),
            statusCode = HttpStatusCode.fromValue(status),
            requestTime = GMTDate(requestTime),
            responseTime = GMTDate(responseTime),
            version = HttpProtocolVersion.parse(version),
            expires = GMTDate(expires),
            headers = Headers.build {
                headers.forEach { (name, value) -> append(name, value) }
            },
            varyKeys = varyKeys,
            body = Base64.decode(body),
        )

        companion object {
            fun from(data: CachedResponseData) = StoredVariant(
                url = data.url.toString(),
                status = data.statusCode.value,
                requestTime = data.requestTime.timestamp,
                responseTime = data.responseTime.timestamp,
                version = data.version.toString(),
                expires = data.expires.timestamp,
                headers = data.headers.entries().flatMap { entry ->
                    entry.value.map { listOf(entry.key, it) }
                },
                varyKeys = data.varyKeys,
                body = Base64.encode(data.body),
            )
        }
    }

    companion object {
        /** Default cap: enough for a full catalog, small next to any device budget. */
        const val DEFAULT_MAX_BYTES: Long = 32L * 1024 * 1024
        private const val SUFFIX = ".cache"
    }
}
