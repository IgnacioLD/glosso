package com.glosso.server

import com.glosso.server.api.ProfileSyncResponse
import com.glosso.server.generation.InMemoryControlPlaneStore
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSyncRouteTest {

    private val adminToken = "test-admin-token"
    private val ingestToken = "test-ingest-token"

    private fun emptyAssets(): File =
        Files.createTempDirectory("glosso-sync-assets").toFile()

    private fun HttpRequestBuilder.ingestHeader() = header("X-Ingest-Token", ingestToken)

    private val weakTheta = """
        {"deviceId":"device-1","language":"en","cefr":"A1",
         "phonemes":[{"phoneme":"θ","score":43,"confidence":0.8,
                     "errorPattern":"en.th.stopping.t","replacementPhoneme":"t",
                     "position":"initial","attempts":5,"successfulAttempts":0,
                     "trend":"declining"}]}
    """.trimIndent()

    @Test
    fun `sync without a control store is unavailable`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            ingestHeader()
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `a weak sound is accepted and maps to an anonymous need class`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = InMemoryControlPlaneStore(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            ingestHeader()
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ProfileSyncResponse>()
        assertTrue(body.accepted)
        val need = body.needs.single()
        assertEquals("A1", need.cefr)
        assertEquals("pronunciation", need.skill)
        assertEquals("θ", need.phoneme)
        assertEquals("en.th.stopping.t", need.errorPattern)
        assertEquals("t", need.replacementPhoneme)
        assertEquals("initial", need.position)
        assertTrue(need.priority > 0f)
    }

    @Test
    fun `the response never echoes the install id`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = InMemoryControlPlaneStore(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            ingestHeader()
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }

        assertFalse(response.bodyAsText().contains("device-1"))
    }

    @Test
    fun `audio is rejected outright`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = InMemoryControlPlaneStore(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            ingestHeader()
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"device-1","audioBase64":"AAAA"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("audio", ignoreCase = true))
    }

    @Test
    fun `nested personal data is rejected`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = InMemoryControlPlaneStore(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            ingestHeader()
            contentType(ContentType.Application.Json)
            setBody(
                """{"deviceId":"device-1","phonemes":[],"meta":{"email":"a@b.example"}}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `an invalid score is a bad request`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = InMemoryControlPlaneStore(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            ingestHeader()
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"device-1","phonemes":[{"phoneme":"θ","score":200}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `sync without the ingest token is rejected and stores nothing`() = testApplication {
        val store = InMemoryControlPlaneStore()
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = store,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(store.openNeeds().isEmpty(), "a rejected ingest must not store a need")
    }

    @Test
    fun `sync with a wrong ingest token is rejected`() = testApplication {
        val store = InMemoryControlPlaneStore()
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = store,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            header("X-Ingest-Token", "not-the-token")
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(store.openNeeds().isEmpty())
    }

    @Test
    fun `a configured ingest token is trimmed before comparison`() = testApplication {
        val store = InMemoryControlPlaneStore()
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = "  $ingestToken\n",
                controlStore = store,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            ingestHeader()
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `sync fails closed when no ingest token is configured`() = testApplication {
        val store = InMemoryControlPlaneStore()
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                controlStore = store,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/profiles/sync") {
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(store.openNeeds().isEmpty())
    }
}
