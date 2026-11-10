package com.glosso.server

import com.glosso.server.api.DemandAggregateResponse
import com.glosso.server.api.NeedClassListResponse
import com.glosso.server.api.PoolConfigDto
import com.glosso.server.generation.InMemoryControlPlaneStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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

/**
 * The end-to-end flow the brief asks for: a weak sound becomes a need, the need
 * becomes an anonymous Need Class, and the Demand Aggregator measures the gap.
 * Nothing is generated in this phase.
 */
class NeedEngineE2ETest {

    private val adminToken = "test-admin-token"
    private val ingestToken = "test-ingest-token"

    private fun emptyAssets(): File =
        Files.createTempDirectory("glosso-e2e-assets").toFile()

    private suspend fun HttpClient.syncWeakTheta(deviceId: String): HttpStatusCode =
        post("/v1/profiles/sync") {
            header("X-Ingest-Token", ingestToken)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"deviceId":"$deviceId","language":"en","cefr":"A1",
                 "phonemes":[{"phoneme":"θ","score":43,"confidence":0.8,
                             "errorPattern":"en.th.stopping.t","replacementPhoneme":"t",
                             "position":"initial","attempts":5,"successfulAttempts":0,
                             "trend":"declining"}]}
                """.trimIndent(),
            )
        }.status

    private suspend fun HttpClient.aggregate(): DemandAggregateResponse =
        post("/v1/admin/demand/aggregate") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.body()

    private suspend fun HttpClient.needClasses(): NeedClassListResponse =
        get("/v1/admin/need-classes") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.body()

    @Test
    fun `weakness becomes a need, a need class and measurable demand`() = testApplication {
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

        assertEquals(HttpStatusCode.OK, client.syncWeakTheta("device-1"))

        val run = client.aggregate()
        assertEquals(1, run.needClasses)
        assertEquals(1, run.needs)
        assertEquals(1, run.learners)
        assertEquals(1, run.belowMinimum)

        val snapshot = client.needClasses()
        assertEquals(PoolConfigDto(20, 50, 100), snapshot.pool)
        val needClass = snapshot.classes.single()
        assertEquals("A1", needClass.cefr)
        assertEquals("θ", needClass.phoneme)
        assertEquals("en.th.stopping.t", needClass.errorPattern)
        assertEquals("initial", needClass.position)
        assertEquals(1, needClass.learners)
        assertEquals(0, needClass.availableEffective)
        assertEquals(50, needClass.gap)
        assertEquals("BELOW_MIN", needClass.gapState)
        assertTrue(needClass.priority > 0.0)

        // A second learner with the same need raises the demand for the class.
        assertEquals(HttpStatusCode.OK, client.syncWeakTheta("device-2"))
        val second = client.aggregate()
        assertEquals(2, second.learners)
        val after = client.needClasses().classes.single()
        assertEquals(2, after.learners)
        assertTrue(after.priority > needClass.priority, "priority must grow with demand")
    }

    @Test
    fun `the demand views are admin only`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = InMemoryControlPlaneStore(),
            )
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/admin/need-classes").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/admin/demand/aggregate").status,
        )
    }
}
