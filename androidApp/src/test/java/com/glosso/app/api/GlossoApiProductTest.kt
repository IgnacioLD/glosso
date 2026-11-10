package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The product config and identity calls: the device id must ride on them, and the
 * exact server shapes must decode. `/v1/config` is `no-cache` so the server's
 * ETag can answer 304, exactly like the content manifest.
 */
class GlossoApiProductTest {

    private fun engine(onRequest: (String?, String) -> Unit) = MockEngine { request ->
        onRequest(request.headers["X-Device-Id"], request.url.encodedPath)
        when (request.url.encodedPath) {
            "/v1/config" -> respond(
                """{"schema":1,"mode":"BETA","beta":{"active":true,"endDate":"2026-12-31T23:59:59Z"},"limits":null,"pricing":null,"flags":{"pro":false}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )

            "/v1/me" -> respond(
                """{"plan":"FREE","status":"NONE","betaFounder":true,"betaFounderBenefit":"Ventaja Beta Founder"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )

            else -> respond("{}", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `config and me carry the device id and decode`() = runTest {
        var configDevice: String? = null
        var meDevice: String? = null
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine { device, path ->
                when (path) {
                    "/v1/config" -> configDevice = device
                    "/v1/me" -> meDevice = device
                }
            },
            contentCache = null,
            deviceIdProvider = { "device-123" },
        )

        val config = api.productConfig()
        val me = api.me()

        assertEquals("device-123", configDevice)
        assertEquals("device-123", meDevice)
        assertEquals("BETA", config.mode)
        assertEquals("2026-12-31T23:59:59Z", config.beta.endDate)
        assertEquals("FREE", me.plan)
        assertEquals(true, me.betaFounder)
    }

    @Test
    fun `a build with no identity sends no device header`() = runTest {
        var seen: String? = "unset"
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine { device, _ -> seen = device },
            contentCache = null,
            deviceIdProvider = { null },
        )

        api.productConfig()

        assertNull(seen)
    }

    @Test
    fun `an error body from me is not decoded into a default FREE account`() = runTest {
        // This PR adds a 409 to /v1/me (account changed meanwhile). With a plain
        // body() call the error body would decode into AccountState defaults and
        // the repository would persist FREE over the last good state.
        val engine = MockEngine {
            respond(
                """{"error":"The account changed meanwhile; try again"}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = null,
            deviceIdProvider = { "device-1" },
        )

        val failure = runCatching { api.me() }.exceptionOrNull()

        assertEquals(409, (failure as? ApiStatusException)?.status)
    }

    @Test
    fun `an error body from config is rejected instead of decoded`() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":"boom"}""",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engine,
            contentCache = null,
            deviceIdProvider = { "device-1" },
        )

        val failure = runCatching { api.productConfig() }.exceptionOrNull()

        assertEquals(500, (failure as? ApiStatusException)?.status)
    }
}
