package com.glosso.app.api

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

/**
 * The Android `downloadTo` on okio: streams [url] to `<target>.part`, resumes a
 * partial file with a byte range (If-Range on its ETag), and only renames it onto
 * [target] once it is complete and verified (length, a server `Digest`, and
 * [expectedSha256]). It runs in the app's process (ForegroundDownloader).
 * TODO(ios phase 3): a background `URLSession`, so a download survives leaving the app.
 */
actual suspend fun GlossoApi.downloadToPath(
    url: String,
    target: Path,
    expectedBytes: Long?,
    expectedSha256: String?,
    onProgress: (Long, Long?) -> Unit,
) {
    val fs = FileSystem.SYSTEM
    // A file only ever appears at [target] once complete and verified.
    fs.metadataOrNull(target)?.size?.takeIf { it > 0 }?.let { size ->
        onProgress(size, size)
        return
    }

    val parent = target.parent ?: throw IOException("No parent directory for $target")
    val partial = parent / (target.name + PART_SUFFIX)
    val meta = parent / (target.name + PART_META_SUFFIX)
    val previous = readPartMeta(fs, meta)
    val partialSize = fs.metadataOrNull(partial)?.size ?: 0L
    val canResume = partialSize > 0 && previous?.url == url
    var offset = if (canResume) partialSize else 0L
    if (!canResume) {
        fs.delete(partial, mustExist = false)
        fs.delete(meta, mustExist = false)
    }

    client.prepareGet(url) {
        // Streamed to a file here; the HTTP cache must not buffer the body.
        headers { append(HttpHeaders.CacheControl, "no-store") }
        // No whole-request limit for a large file; inactivity still fails it.
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = DOWNLOAD_READ_TIMEOUT_MS
        }
        if (offset > 0) {
            headers { append(HttpHeaders.Range, "bytes=$offset-") }
            previous?.etag?.takeIf { it.isNotBlank() }?.let {
                headers { append(HttpHeaders.IfRange, it) }
            }
        }
    }.execute { response ->
        val status = response.status
        val etag = response.headers[HttpHeaders.ETag]
        val contentRangeTotal = response.headers[HttpHeaders.ContentRange]
            ?.substringAfterLast('/')?.toLongOrNull()
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val digestSha256 = parseDigestSha256(response.headers["Digest"])
        val resumed = status == HttpStatusCode.PartialContent
        if (!resumed && offset > 0) {
            // The server ignored the range or the file changed: restart.
            offset = 0L
            fs.delete(partial, mustExist = false)
        }
        if (!resumed && status.value !in 200..299) {
            throw IOException("Download failed: HTTP ${status.value} for $url")
        }
        val total = if (resumed) contentRangeTotal ?: contentLength?.let { offset + it } else contentLength
        fs.createDirectories(parent)
        writePartMeta(fs, meta, url, etag)
        val sink = if (offset > 0) fs.appendingSink(partial) else fs.sink(partial)
        sink.buffer().use { out ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_SIZE)
            var written = offset
            onProgress(written, total)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                out.write(buffer, 0, read)
                written += read
                onProgress(written, total)
            }
        }
        val size = fs.metadataOrNull(partial)?.size ?: 0L
        if (size == 0L) throw IOException("Empty download for $url")
        if (total != null && size != total) throw IOException("Incomplete download: $size of $total bytes")
        // As on Android: a content-encoded body has no comparable size, so
        // [expectedBytes] only binds when the response declared a length.
        if (expectedBytes != null && total != null && size != expectedBytes) {
            throw IOException("Unexpected size: $size of $expectedBytes bytes")
        }
        if (digestSha256 != null || expectedSha256 != null) {
            val digest = sha256(fs, partial)
            val badDigest = digestSha256 != null && base64(digest) != digestSha256
            val badChecksum = expectedSha256 != null && !hex(digest).equals(expectedSha256, ignoreCase = true)
            if (badDigest || badChecksum) {
                fs.delete(partial, mustExist = false)
                fs.delete(meta, mustExist = false)
                throw IOException("Checksum mismatch for $url")
            }
        }
        fs.delete(target, mustExist = false)
        fs.atomicMove(partial, target)
        fs.delete(meta, mustExist = false)
    }
}

private const val PART_SUFFIX = ".part"
private const val PART_META_SUFFIX = ".part.meta"
private const val BUFFER_SIZE = 64 * 1024

/** No bytes for this long means the connection is dead; fail and resume. */
private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000L

private data class PartMeta(val url: String, val etag: String?)

private fun readPartMeta(fs: FileSystem, file: Path): PartMeta? = runCatching {
    if (!fs.exists(file)) return@runCatching null
    val lines = fs.read(file) { readUtf8() }.lines()
    PartMeta(lines.getOrElse(0) { "" }, lines.getOrNull(1))
}.getOrNull()

private fun writePartMeta(fs: FileSystem, file: Path, url: String, etag: String?) {
    runCatching { fs.write(file) { writeUtf8(url + "\n" + (etag ?: "")) } }
}

/** Extracts the base64 sha-256 value from a `Digest` header, if present. */
private fun parseDigestSha256(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val part = header.split(',').map { it.trim() }.firstOrNull {
        it.substringBefore('=').trim().equals("sha-256", ignoreCase = true)
    } ?: return null
    return part.substringAfter('=', "").trim().trim(':').takeIf { it.isNotEmpty() }
}

/** SHA-256 of [file], streamed through CommonCrypto (the model is hundreds of MB). */
@OptIn(ExperimentalForeignApi::class)
private fun sha256(fs: FileSystem, file: Path): ByteArray = memScoped {
    val context = alloc<CC_SHA256_CTX>()
    CC_SHA256_Init(context.ptr)
    fs.source(file).buffer().use { source ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == -1) break
            if (read == 0) continue
            buffer.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(0), read.toUInt()) }
        }
    }
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { CC_SHA256_Final(it.addressOf(0).reinterpret(), context.ptr) }
    digest
}

private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

@OptIn(ExperimentalEncodingApi::class)
private fun base64(bytes: ByteArray): String = Base64.encode(bytes)
