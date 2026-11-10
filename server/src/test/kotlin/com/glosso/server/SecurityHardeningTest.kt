package com.glosso.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.glosso.server.api.ScoreRequest

/**
 * Pins the hardening that does not need a live edge: the body is capped before
 * it is read, the version banner is gone, the admin query token is refused, the
 * security headers are present, and a rotating `X-Forwarded-For` cannot evade
 * the per-IP rate limit because the key comes from the edge-resolved client IP.
 */
class SecurityHardeningTest {

    private fun emptyAssets(): File =
        Files.createTempDirectory("glosso-security-assets").toFile()

    private suspend fun io.ktor.client.HttpClient.score(
        realIp: String,
        forwardedFor: String?,
    ): HttpStatusCode {
        val response = post("/v1/score") {
            header("X-Real-Client-IP", realIp)
            forwardedFor?.let { header("X-Forwarded-For", it) }
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }
        return response.status
    }

    @Test
    fun `health no longer exposes the server version`() = testApplication {
        application { module(assetsDir = emptyAssets(), adminToken = null, analytics = null) }

        val body = client.get("/health").bodyAsText()

        assertFalse(body.contains("version"), "health leaked a version field: $body")
    }

    @Test
    fun `security headers are present but HSTS only over https`() = testApplication {
        application { module(assetsDir = emptyAssets(), adminToken = null, analytics = null) }

        // Plain http (the test host): no HSTS, but the rest of the headers are on.
        val http = client.get("/health").headers
        assertTrue(http[HttpHeaders.StrictTransportSecurity].isNullOrEmpty(), "HSTS over http")
        assertEquals("nosniff", http["X-Content-Type-Options"])
        assertEquals("no-referrer", http["Referrer-Policy"])
        assertTrue(http["Content-Security-Policy"].orEmpty().contains("default-src 'none'"))
        assertTrue(http["Permissions-Policy"].orEmpty().contains("microphone=()"))

        // Behind Caddy the client-facing hop is https and Caddy sets the header.
        val https = client.get("/health") {
            header("X-Forwarded-Proto", "https")
        }.headers
        assertTrue(
            https[HttpHeaders.StrictTransportSecurity].orEmpty().contains("max-age=15552000"),
            "HSTS missing over https: ${https[HttpHeaders.StrictTransportSecurity]}",
        )
    }

    @Test
    fun `health and assets both still answer 200`() = testApplication {
        val assets = emptyAssets()
        File(assets, "ok.txt").writeText("fine")
        application { module(assetsDir = assets, adminToken = null, analytics = null) }

        assertEquals(HttpStatusCode.OK, client.get("/health").status)

        val asset = client.get("/assets/ok.txt")
        assertEquals(HttpStatusCode.OK, asset.status)
        assertTrue(asset.bodyAsText().contains("fine"))
    }

    @Test
    fun `an oversized declared body is rejected before it is read`() = testApplication {
        application { module(assetsDir = emptyAssets(), adminToken = null, analytics = null) }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody("x".repeat(9_000_001))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `the admin query token is refused, the header still works`() = testApplication {
        application { module(assetsDir = emptyAssets(), adminToken = "secret", analytics = null) }

        val query = client.get("/v1/events/summary?token=secret")
        assertEquals(HttpStatusCode.Unauthorized, query.status)

        val header = client.get("/v1/events/summary") {
            header(HttpHeaders.Authorization, "Bearer secret")
        }
        // No analytics store configured, so the gate passes and the route says so.
        assertEquals(HttpStatusCode.ServiceUnavailable, header.status)
    }

    @Test
    fun `phonemize requires the client key when one is configured`() = testApplication {
        application {
            module(assetsDir = emptyAssets(), adminToken = null, analytics = null, apiKey = "client-key")
        }

        val response = client.post("/v1/phonemize") {
            contentType(ContentType.Application.Json)
            setBody("""{"audioBase64":"AAAA"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `assets do not serve files outside the root via traversal`() = testApplication {
        val parent = Files.createTempDirectory("glosso-traversal").toFile()
        val assets = File(parent, "assets").apply { mkdirs() }
        File(parent, "secret.txt").writeText("TOP-SECRET")
        File(assets, "ok.txt").writeText("fine")
        File(assets, ".env").writeText("TOP-SECRET")
        application { module(assetsDir = assets, adminToken = null, analytics = null) }

        // A normal asset is still served.
        assertTrue(client.get("/assets/ok.txt").bodyAsText().contains("fine"))

        // A symlink inside the tree that points outside it must also be refused:
        // canonicalFile resolves it and the inside(root) check rejects it.
        Files.createSymbolicLink(
            File(assets, "link.txt").toPath(),
            File(parent, "secret.txt").toPath(),
        )

        val attempts = listOf(
            "/assets/../secret.txt",
            "/assets/%2e%2e/secret.txt",
            "/assets/..%2fsecret.txt",
            "/assets/%2e%2e%2fsecret.txt",
            "/assets/.env",
            "/assets/link.txt",
            "/assets//etc/passwd",
            "/assets/%2fetc%2fpasswd",
            "/assets/..%5csecret.txt",
        )
        for (path in attempts) {
            val body = client.get { url { encodedPath = path } }.bodyAsText()
            assertFalse(body.contains("TOP-SECRET"), "leak through $path")
            assertFalse(body.contains("root:"), "absolute path leak through $path")
        }
    }

    @Test
    fun `the access log line never carries a secret from query or headers`() = testApplication {
        val captured = mutableListOf<String>()
        application {
            module(
                assetsDir = emptyAssets(),
                adminToken = "ADMIN-SECRET",
                analytics = null,
                apiKey = "API-KEY-SECRET",
                ingestToken = "INGEST-SECRET",
            )
            // Probe the exact production formatter against calls that carry the
            // secrets in every shape a caller could try.
            install(
                createApplicationPlugin("CaptureAccessLine") {
                    onCall { call -> captured += call.accessLogLine() }
                },
            )
        }

        client.get("/admin/events?token=ADMIN-SECRET")
        client.post("/v1/score?api_key=API-KEY-SECRET") { header("X-Api-Key", "API-KEY-SECRET") }
        client.post("/v1/profiles/sync?token=INGEST-SECRET") { header("X-Ingest-Token", "INGEST-SECRET") }

        assertTrue(captured.isNotEmpty(), "no access log line was captured")
        val secrets = listOf("ADMIN-SECRET", "API-KEY-SECRET", "INGEST-SECRET")
        for (line in captured) {
            for (secret in secrets) {
                assertFalse(line.contains(secret), "secret leaked into the access log line: $line")
            }
        }
    }

    @Test
    fun `cors origins are explicit and never a wildcard`() {
        assertEquals(listOf("glossostudio.com" to "https"), parseCorsOrigins("https://glossostudio.com"))
        assertEquals(listOf("glossostudio.com" to "https"), parseCorsOrigins("glossostudio.com"))
        assertEquals(
            listOf("glossostudio.com" to "https", "www.glossostudio.com" to "https"),
            parseCorsOrigins("https://glossostudio.com, https://www.glossostudio.com"),
        )
        assertTrue(parseCorsOrigins("*").isEmpty())
        assertTrue(parseCorsOrigins("").isEmpty())
        assertTrue(parseCorsOrigins(null).isEmpty())
    }

    @Test
    fun `rotating X-Forwarded-For does not evade the per-IP limit`() = testApplication {
        application { module(assetsDir = emptyAssets(), adminToken = null, analytics = null) }
        val json = createClient { install(ContentNegotiation) { json() } }

        for (i in 0 until 60) {
            val status = json.score(realIp = "203.0.113.7", forwardedFor = "198.51.100.$i")
            assertEquals(HttpStatusCode.OK, status)
        }

        val blocked = json.score(realIp = "203.0.113.7", forwardedFor = "198.51.100.99")
        assertEquals(HttpStatusCode.TooManyRequests, blocked)

        // A different real client is not caught by someone else's exhausted budget.
        val other = json.score(realIp = "203.0.113.8", forwardedFor = "198.51.100.1")
        assertEquals(HttpStatusCode.OK, other)
    }
}
