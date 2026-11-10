package com.glosso.server

import com.glosso.server.api.GenerationRunResponse
import com.glosso.server.api.ScoreRequest
import com.glosso.server.generation.CompositeContentCatalog
import com.glosso.server.generation.DemandService
import com.glosso.server.generation.EmptyContentCatalog
import com.glosso.server.generation.GenerationConfig
import com.glosso.server.generation.GenerationRuntime
import com.glosso.server.generation.GenerationScheduler
import com.glosso.server.generation.GenerationService
import com.glosso.server.generation.InMemoryControlPlaneStore
import com.glosso.server.generation.InMemoryGenerationQueue
import com.glosso.server.generation.InMemoryGenerationStore
import com.glosso.server.generation.PoolConfig
import com.glosso.server.generation.SchedulerConfig
import com.glosso.server.generation.TemplateCandidateSource
import com.glosso.server.generation.TtsWorker
import com.glosso.server.generation.ttsHarness
import com.glosso.server.tts.echoingFakeProvider
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
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ingest seam is the only unauthenticated-input path into generation. Left
 * open, anyone could inflate demand and make the scheduler spend real TTS money.
 * These tests pin the gate: no token means 401 and no queued work; the right
 * token keeps the pipeline working.
 */
class ProfilesSyncAuthTest {

    private val adminToken = "test-admin-token"
    private val ingestToken = "test-ingest-token"

    private val weakTheta = """
        {"deviceId":"device-1","language":"en","cefr":"A1",
         "phonemes":[{"phoneme":"θ","score":43,"confidence":0.8,
                     "errorPattern":"en.th.stopping.t","replacementPhoneme":"t",
                     "position":"initial","attempts":5,"successfulAttempts":0,
                     "trend":"declining"}]}
    """.trimIndent()

    private class Harness(
        val control: InMemoryControlPlaneStore,
        val generation: GenerationRuntime,
    )

    private fun harness(): Harness {
        val pool = PoolConfig.of(2, 3, 5)
        val control = InMemoryControlPlaneStore()
        val store = InMemoryGenerationStore()
        val queue = InMemoryGenerationQueue()
        val config = GenerationConfig(provider = "fake", model = "fake-model", retryBackoffMs = 0, workerBlockMs = 0)
        val catalog = CompositeContentCatalog.of(EmptyContentCatalog, CompositeContentCatalog.PoolCatalog(store))
        val demand = DemandService(control, catalog, pool)
        val service = GenerationService(control, store, queue, TemplateCandidateSource(), pool, config)
        val worker = TtsWorker(queue, store, ttsHarness(echoingFakeProvider()).processor, config)
        val runtime = GenerationRuntime(
            store = store,
            queue = queue,
            service = service,
            worker = worker,
            scheduler = GenerationScheduler(demand, service, SchedulerConfig(1), CoroutineScope(Dispatchers.Default)),
            scope = CoroutineScope(Dispatchers.Default),
            workerBlockMs = 0,
            resources = emptyList(),
        )
        return Harness(control, runtime)
    }

    private fun applicationTest(block: suspend (HttpClient) -> Unit) = testApplication {
        val h = harness()
        application {
            module(
                Files.createTempDirectory("glosso-ingest-auth").toFile(),
                adminToken = adminToken,
                analytics = null,
                ingestToken = ingestToken,
                controlStore = h.control,
                contentCatalog = CompositeContentCatalog.of(
                    EmptyContentCatalog,
                    CompositeContentCatalog.PoolCatalog(h.generation.store),
                ),
                generation = h.generation,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client)
    }

    private suspend fun HttpClient.sync(token: String?): HttpStatusCode =
        post("/v1/profiles/sync") {
            token?.let { header("X-Ingest-Token", it) }
            contentType(ContentType.Application.Json)
            setBody(weakTheta)
        }.status

    private suspend fun HttpClient.runGenerationCycle(): GenerationRunResponse =
        post("/v1/admin/generation/run") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }.body()

    @Test
    fun `without the ingest token the sync is 401 and the cycle enqueues nothing`() = applicationTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.sync(token = null))
        assertEquals(HttpStatusCode.Unauthorized, client.sync(token = "wrong-token"))

        val run = client.runGenerationCycle()
        assertEquals(0, run.classesConsidered)
        assertEquals(0, run.jobsCreated)
        assertEquals(0, run.messagesEnqueued)
    }

    @Test
    fun `with the ingest token the sync still derives needs and the cycle enqueues`() = applicationTest { client ->
        assertEquals(HttpStatusCode.OK, client.sync(token = ingestToken))

        val run = client.runGenerationCycle()
        assertEquals(1, run.classesConsidered)
        assertEquals(3, run.jobsCreated)
        assertEquals(3, run.messagesEnqueued)
    }

    @Test
    fun `the public routes stay open without any credential`() = applicationTest { client ->
        assertEquals(HttpStatusCode.OK, client.get("/health").status)

        // /v1/score is open when GLOSSO_API_KEY is unset: preexisting fail-open
        // behaviour for dev/emulator, not introduced here. Production sets the
        // key. Tracked separately; this pins that the ingest gate did not change it.
        val score = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }
        assertEquals(HttpStatusCode.OK, score.status)
    }
}
