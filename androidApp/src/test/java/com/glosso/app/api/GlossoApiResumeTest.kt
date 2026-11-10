package com.glosso.app.api

import java.io.File
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The resumable transfer's contract: continue from a partial with Range, restart
 * when the server ignores it, and never let a half file look complete.
 */
class GlossoApiResumeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class VersionStore : AssetVersionStore {
        override fun load(): String? = null
        override fun save(version: String) = Unit
    }

    private fun api(engine: MockEngine) = GlossoApi(
        baseUrl = "http://test",
        engine = engine,
        contentCache = null,
        versionStore = VersionStore(),
    )

    private val payload = ByteArray(1000) { (it % 251).toByte() }

    @Test
    fun `resumes from a partial file with a range request`() = runTest {
        var rangeHeader: String? = null
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            rangeHeader = request.headers[HttpHeaders.Range]
            val start = rangeHeader?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            respond(
                body,
                status = HttpStatusCode.PartialContent,
                headers = Headers.build {
                    append(HttpHeaders.ContentLength, body.size.toString())
                    append(HttpHeaders.ContentRange, "bytes $start-${payload.size - 1}/${payload.size}")
                    append(HttpHeaders.ETag, "\"v1\"")
                },
            )
        }
        val target = File(folder.root, "asset.bin")
        // A previous attempt left half the file and its validator behind.
        val half = payload.size / 2
        File(folder.root, "asset.bin.part").writeBytes(payload.copyOfRange(0, half))
        File(folder.root, "asset.bin.part.meta").writeText("http://test/asset.bin\n\"v1\"")

        api(engine).downloadTo("http://test/asset.bin", target)

        assertEquals("bytes=$half-", rangeHeader)
        assertArrayEquals(payload, target.readBytes())
        assertEquals(1, requests)
        assertFalse(File(folder.root, "asset.bin.part").exists())
    }

    @Test
    fun `restarts from zero when the server ignores the range`() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            respond(
                payload,
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentLength, payload.size.toString())
                },
            )
        }
        val target = File(folder.root, "asset.bin")
        File(folder.root, "asset.bin.part").writeBytes(payload.copyOfRange(0, 100))
        File(folder.root, "asset.bin.part.meta").writeText("http://test/asset.bin\n\"v1\"")

        api(engine).downloadTo("http://test/asset.bin", target)

        assertArrayEquals(payload, target.readBytes())
        assertEquals(1, requests)
    }

    @Test
    fun `an incomplete body is rejected and the partial is kept`() = runTest {
        val engine = MockEngine {
            respond(
                payload.copyOfRange(0, 10),
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    // Promises 1000 bytes but sends 10: a dropped connection.
                    append(HttpHeaders.ContentLength, payload.size.toString())
                },
            )
        }
        val target = File(folder.root, "asset.bin")
        var failed = false
        try {
            api(engine).downloadTo("http://test/asset.bin", target)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("the incomplete download must fail", failed)
        assertFalse("a half file is never promoted", target.exists())
        assertTrue("the partial is kept for resume", File(folder.root, "asset.bin.part").exists())
    }

    @Test
    fun `a server Content-MD5 is verified and a mismatch is rejected`() = runTest {
        val good = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("MD5").digest(payload),
        )
        val okEngine = MockEngine {
            respond(
                payload,
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentLength, payload.size.toString())
                    append("Content-MD5", good)
                },
            )
        }
        val okTarget = File(folder.root, "ok.bin")
        api(okEngine).downloadTo("http://test/ok.bin", okTarget)
        assertArrayEquals(payload, okTarget.readBytes())

        val badEngine = MockEngine {
            respond(
                payload,
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentLength, payload.size.toString())
                    append("Content-MD5", "AAAAAAAAAAAAAAAAAAAAAA==")
                },
            )
        }
        val badTarget = File(folder.root, "bad.bin")
        var failed = false
        try {
            api(badEngine).downloadTo("http://test/bad.bin", badTarget)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("a wrong digest must be rejected", failed)
        assertFalse(badTarget.exists())
    }

    @Test
    fun `an empty body is never promoted to a finished file`() = runTest {
        val engine = MockEngine {
            respond(
                ByteArray(0),
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentLength, "0") },
            )
        }
        val target = File(folder.root, "asset.bin")
        var failed = false
        try {
            api(engine).downloadTo("http://test/asset.bin", target)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("an empty download must fail", failed)
        assertFalse(target.exists())
    }

    @Test
    fun `a length-less body is accepted after it is read to the end`() = runTest {
        // Compressed assets (the SVG art goes through gzip) arrive with no
        // Content-Length: refusing them would fail every lesson that ships art.
        val engine = MockEngine {
            respond(
                io.ktor.utils.io.ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = Headers.Empty,
            )
        }
        val target = File(folder.root, "compressed.svg")
        api(engine).downloadTo("http://test/compressed.svg", target)
        assertTrue("the decoded body is kept", target.exists())
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `a size measured on the compressed form does not fail a length-less body`() = runTest {
        // The probe can see the compressed Content-Length; the decoded body is
        // bigger. With no length on the wire the two are not comparable.
        val engine = MockEngine {
            respond(
                io.ktor.utils.io.ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = Headers.Empty,
            )
        }
        val target = File(folder.root, "compressed.svg")
        api(engine).downloadTo("http://test/compressed.svg", target, expectedBytes = 1L)
        assertTrue("the decoded body is kept", target.exists())
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `a complete file on disk is not downloaded again`() = runTest {
        val engine = MockEngine { respond(payload) }
        val target = File(folder.root, "asset.bin")
        File(folder.root, "asset.bin").writeBytes(payload)

        api(engine).downloadTo("http://test/asset.bin", target)

        assertEquals("no request for a file we already hold", 0, engine.requestHistory.size)
    }

    @Test
    fun `the published sha256 sidecar is read and a 404 means no digest`() = runTest {
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload).joinToString("") { "%02x".format(it) }
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith(".sha256") -> respond(hex)
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = api(engine)
        assertEquals(hex, api.sharedAssetSha256("model.bin"))

        val missing = MockEngine { respond("nope", HttpStatusCode.NotFound) }
        assertEquals(null, api(missing).sharedAssetSha256("model.bin"))
    }

    @Test
    fun `an expected size mismatch discards the file`() = runTest {
        val engine = MockEngine {
            respond(
                payload,
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentLength, payload.size.toString()) },
            )
        }
        val target = File(folder.root, "asset.bin")
        var failed = false
        try {
            api(engine).downloadTo("http://test/asset.bin", target, expectedBytes = payload.size - 1L)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("a size that does not match the manifest must be rejected", failed)
        assertFalse(target.exists())
    }

    @Test
    fun `a checksum mismatch discards the file`() = runTest {
        val engine = MockEngine { request ->
            respond(
                payload,
                status = HttpStatusCode.OK,
                headers = Headers.build {
                    append(HttpHeaders.ContentLength, payload.size.toString())
                },
            )
        }
        val target = File(folder.root, "asset.bin")
        var failed = false
        try {
            api(engine).downloadTo(
                "http://test/asset.bin",
                target,
                expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            )
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("a corrupt file must be rejected", failed)
        assertFalse(target.exists())
    }
}
