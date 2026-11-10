package com.glosso.app.api

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.File

// Streaming a download to a java.io.File is Android-only; the shared code reaches
// it through downloadToPath, and iOS gets a background URLSession (phase 3).

/**
 * Streams [url] straight to [target], resuming a previous partial download
 * when the server supports byte ranges. [onProgress] reports the bytes
 * written (including any resumed prefix) and the total from Content-Length
 * or Content-Range, null when the server does not say.
 *
 * The body goes to `<target>.part` and is only renamed onto [target] after
 * it is complete and verified, so a half file is never mistaken for a good
 * one. On any failure, including cancellation, the partial file and the
 * small `.part.meta` sidecar are kept: the next attempt continues where it
 * left off instead of starting from zero. A digest ([expectedSha256], a
 * server `Content-MD5`/`Digest`) or the response's own length must match, or
 * the partial is discarded and the caller retries. [expectedBytes] is only
 * enforced when the response carries a length: a content-encoded body is
 * decoded by Ktor, so a size measured elsewhere is not comparable and the
 * body read to EOF is what proves it whole.
 */
suspend fun GlossoApi.downloadTo(
    url: String,
    target: File,
    expectedBytes: Long? = null,
    expectedSha256: String? = null,
    onProgress: (Long, Long?) -> Unit = { _, _ -> },
) {
    // A file only ever appears at [target] once it is complete and verified,
    // so an existing one is kept without touching the network: re-downloading
    // what is already on disk is the other half of "downloads feel slow".
    if (target.isFile && target.length() > 0) {
        onProgress(target.length(), target.length())
        return
    }

    val parent = target.parentFile ?: File(".")
    val partial = File(parent, target.name + PART_SUFFIX)
    val meta = File(parent, target.name + PART_META_SUFFIX)
    val previous = readPartMeta(meta)
    val canResume = partial.isFile && partial.length() > 0 && previous?.url == url
    var offset = if (canResume) partial.length() else 0L
    if (!canResume) {
        partial.delete()
        meta.delete()
    }

    client.prepareGet(url) {
        // no-store, not no-cache: with the HTTP cache plugin a cacheable
        // response is read fully into memory before it is stored, which OOMs
        // on the ~600 MB model. This call streams to a file itself, so the
        // HTTP cache has nothing useful to do with the body.
        headers { append(HttpHeaders.CacheControl, "no-store") }
        // A large model takes far longer than the normal request budget, so
        // the whole-request timeout is lifted. The socket timeout is not: it
        // is an inactivity timeout, and it is what turns a silently dropped
        // connection (a network the phone lost without a FIN) into a failure
        // the queue can retry and resume, instead of hanging forever.
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = DOWNLOAD_READ_TIMEOUT_MS
        }
        if (offset > 0) {
            headers { append(HttpHeaders.Range, "bytes=$offset-") }
            // If-Range makes the server answer the whole file again when it
            // changed since the partial was fetched, instead of appending a
            // new version onto an old prefix.
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
        // Optional server digests: verified when present, so integrity does
        // not depend only on the length the same server just told us.
        val contentMd5 = response.headers["Content-MD5"]
        val digestSha256 = parseDigestSha256(response.headers["Digest"])
        val resumed = status == HttpStatusCode.PartialContent
        if (!resumed && offset > 0) {
            // The server ignored the range or the file changed: restart.
            offset = 0L
            partial.delete()
        }
        if (!resumed && status.value !in 200..299) {
            throw java.io.IOException("Download failed: HTTP ${status.value} for $url")
        }
        val total = if (resumed) {
            contentRangeTotal ?: contentLength?.let { offset + it }
        } else {
            contentLength
        }
        parent.mkdirs()
        writePartMeta(meta, url, etag)
        java.io.FileOutputStream(partial, offset > 0).use { out ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
        if (partial.length() == 0L) {
            // An empty body is never a finished file, whatever the headers say.
            throw java.io.IOException("Empty download for $url")
        }
        if (total != null && partial.length() != total) {
            throw java.io.IOException("Incomplete download: ${partial.length()} of $total bytes")
        }
        if (expectedBytes != null && partial.length() != expectedBytes) {
            if (total != null) {
                throw java.io.IOException("Unexpected size: ${partial.length()} of $expectedBytes bytes")
            }
            // No length on the wire: the response was content-encoded (the
            // SVG art goes through gzip) and Ktor decoded it, so a size
            // measured elsewhere is not comparable. Keep the body read to
            // EOF instead of failing every artwork asset.
        }
        if (contentMd5 != null && !md5Base64Matches(partial, contentMd5)) {
            partial.delete()
            meta.delete()
            throw java.io.IOException("MD5 mismatch for $url")
        }
        if (digestSha256 != null && !sha256Base64Matches(partial, digestSha256)) {
            partial.delete()
            meta.delete()
            throw java.io.IOException("Digest mismatch for $url")
        }
        if (expectedSha256 != null && !sha256Matches(partial, expectedSha256)) {
            partial.delete()
            meta.delete()
            throw java.io.IOException("Checksum mismatch for $url")
        }
        // A body read to EOF with no declared length (a compressed response)
        // is complete: Ktor raises on a truncated or undecodable stream, so
        // refusing it here would only fail the artwork every lesson ships.
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        meta.delete()
    }
}

private const val PART_SUFFIX = ".part"
private const val PART_META_SUFFIX = ".part.meta"

/** No bytes for this long means the connection is dead; fail and resume. */
private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000L

/** The URL and validator (ETag) a partial file was fetched for, for resume. */
private data class PartMeta(val url: String, val etag: String?)

private fun readPartMeta(file: File): PartMeta? = runCatching {
    if (!file.isFile) return@runCatching null
    val lines = file.readLines()
    PartMeta(lines.getOrElse(0) { "" }, lines.getOrNull(1))
}.getOrNull()

private fun writePartMeta(file: File, url: String, etag: String?) {
    runCatching { file.writeText(url + "\n" + (etag ?: "")) }
}

private fun sha256Matches(file: File, expected: String): Boolean = runCatching {
    digestOf(file, "SHA-256").joinToString("") { "%02x".format(it) }
        .equals(expected, ignoreCase = true)
}.getOrDefault(false)

/** `Content-MD5` is base64 of the 16 raw MD5 bytes. */
private fun md5Base64Matches(file: File, expected: String): Boolean = runCatching {
    java.util.Base64.getEncoder().encodeToString(digestOf(file, "MD5")) == expected.trim()
}.getOrDefault(false)

/** RFC 3230/9530 `Digest: sha-256=<base64>`, optional colon wrapper. */
private fun sha256Base64Matches(file: File, expected: String): Boolean = runCatching {
    java.util.Base64.getEncoder().encodeToString(digestOf(file, "SHA-256")) == expected.trim()
}.getOrDefault(false)

/** Extracts the base64 sha-256 value from a `Digest` header, if present. */
private fun parseDigestSha256(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val part = header.split(',').map { it.trim() }.firstOrNull {
        it.substringBefore('=').trim().equals("sha-256", ignoreCase = true)
    } ?: return null
    return part.substringAfter('=', "").trim().trim(':').takeIf { it.isNotEmpty() }
}

private fun digestOf(file: File, algorithm: String): ByteArray {
    val digest = java.security.MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}

actual suspend fun GlossoApi.downloadToPath(
    url: String,
    target: okio.Path,
    expectedBytes: Long?,
    expectedSha256: String?,
    onProgress: (Long, Long?) -> Unit,
) = downloadTo(url, target.toFile(), expectedBytes, expectedSha256, onProgress)
