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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.glosso.server.api.AnalyticsIngestRequest
import com.glosso.server.api.ThemeSuggestionRequest

class ObservabilityRouteTest {

    private val adminToken = "test-admin-token"

    private fun emptyAssets(): File =
        Files.createTempDirectory("glosso-obs-assets").toFile()

    @Test
    fun `metrics is closed without the admin token`() = testApplication {
        application { module(emptyAssets(), adminToken = adminToken, analytics = null) }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `metrics exposes prometheus text with the admin token`() = testApplication {
        application { module(emptyAssets(), adminToken = adminToken, analytics = null) }

        val response = client.get("/metrics") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("jvm_"), "expected JVM metrics, got: ${body.take(200)}")
        assertTrue(body.contains("glosso_"), "expected app metrics, got: ${body.take(200)}")
    }

    @Test
    fun `stats is closed without the admin token`() = testApplication {
        application { module(emptyAssets(), adminToken = adminToken, analytics = null) }

        val response = client.get("/stats")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `stats is readable with the admin token`() = testApplication {
        application { module(emptyAssets(), adminToken = adminToken, analytics = null) }

        val response = client.get("/stats") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `admin analytics page is closed without the admin token`() = testApplication {
        application { module(emptyAssets(), adminToken = adminToken, analytics = null) }

        val response = client.get("/admin/events")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `event ingest reports analytics disabled when no database is configured`() = testApplication {
        application { module(emptyAssets(), adminToken = adminToken, analytics = null) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }

        val response = jsonClient.post("/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(AnalyticsIngestRequest(events = emptyList()))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `theme suggestion reports analytics disabled when no database is configured`() = testApplication {
        application { module(emptyAssets(), adminToken = adminToken, analytics = null) }
        val jsonClient = createClient { install(ContentNegotiation) { json() } }

        val response = jsonClient.post("/v1/themes/suggest") {
            contentType(ContentType.Application.Json)
            setBody(ThemeSuggestionRequest(theme = "football"))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }
}
