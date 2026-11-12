package com.glosso.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web app (the shared app code in a browser) must pass the preflights its requests trigger. */
class CorsTest {

    private val origins = parseCorsOrigins("https://app.glossostudio.com")

    @Test
    fun `a json score post with the app's headers is allowed from the web app`() = testApplication {
        application { installCors(origins) }

        val response = client.options("/v1/score") {
            header(HttpHeaders.Origin, "https://app.glossostudio.com")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type,x-api-key,x-device-id,x-analytics,cache-control")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("https://app.glossostudio.com", response.headers[HttpHeaders.AccessControlAllowOrigin])
        val allowed = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
        listOf("content-type", "x-api-key", "x-device-id", "x-analytics", "cache-control").forEach {
            assertTrue(it in allowed, "$it not allowed: $allowed")
        }
    }

    @Test
    fun `the etag is readable and other origins are refused`() = testApplication {
        application {
            installCors(origins)
            routing { get("/v1/content/manifest") { call.response.header(HttpHeaders.ETag, "\"v1\""); call.respondText("{}") } }
        }

        val ours = client.get("/v1/content/manifest") { header(HttpHeaders.Origin, "https://app.glossostudio.com") }
        assertTrue(HttpHeaders.ETag in ours.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty())

        val other = client.get("/v1/content/manifest") { header(HttpHeaders.Origin, "https://evil.example") }
        assertNull(other.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
