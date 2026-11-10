package com.glosso.server

import com.glosso.server.api.ContentPoolResponse
import com.glosso.server.api.GenerationJobsResponse
import com.glosso.server.api.GenerationRunResponse
import com.glosso.server.api.GenerationStatusResponse
import com.glosso.server.generation.CompositeContentCatalog
import com.glosso.server.generation.ContentStatus
import com.glosso.server.generation.DemandService
import com.glosso.server.generation.EmptyContentCatalog
import com.glosso.server.generation.GenerationConfig
import com.glosso.server.generation.GenerationRuntime
import com.glosso.server.generation.GenerationScheduler
import com.glosso.server.generation.GenerationService
import com.glosso.server.generation.InMemoryControlPlaneStore
import com.glosso.server.generation.InMemoryGenerationQueue
import com.glosso.server.generation.InMemoryGenerationStore
import com.glosso.server.generation.NeedClass
import com.glosso.server.generation.NeedClassKey
import com.glosso.server.generation.PoolConfig
import com.glosso.server.generation.SKILL_PRONUNCIATION
import com.glosso.server.generation.TemplateCandidateSource
import com.glosso.server.generation.TtsWorker
import com.glosso.server.generation.ttsHarness
import com.glosso.server.generation.GapState
import com.glosso.server.tts.echoingFakeProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The admin surface of phase 2: an operator can force a cycle, inspect the queue
 * and the pool, and see which jobs failed. All token protected.
 */
class GenerationAdminRouteTest {

    private val adminToken = "test-admin-token"

    private val key = NeedClassKey(
        language = "en",
        cefr = "A1",
        skill = SKILL_PRONUNCIATION,
        phoneme = "θ",
        errorPattern = "en.th.stopping.t",
        position = "initial",
    )

    private class Harness(
        val control: InMemoryControlPlaneStore,
        val generation: GenerationRuntime,
    )

    private fun harness(): Harness {
        val pool = PoolConfig.of(2, 3, 5)
        val control = InMemoryControlPlaneStore()
        control.upsertNeedClasses(
            listOf(
                NeedClass(
                    key = key,
                    learnerCount = 10,
                    availableEffective = 0,
                    gap = 3,
                    gapState = GapState.BELOW_MIN,
                    priority = 1.0,
                ),
            ),
        )
        val store = InMemoryGenerationStore()
        val queue = InMemoryGenerationQueue()
        val config = GenerationConfig(provider = "fake", model = "fake-model", retryBackoffMs = 0, workerBlockMs = 0)
        val catalog = CompositeContentCatalog.of(EmptyContentCatalog, CompositeContentCatalog.PoolCatalog(store))
        val demand = DemandService(control, catalog, pool)
        val service = GenerationService(control, store, queue, TemplateCandidateSource(), pool, config)
        val worker = TtsWorker(queue, store, ttsHarness(echoingFakeProvider()).processor, config)
        val scheduler = GenerationScheduler(demand, service, com.glosso.server.generation.SchedulerConfig(1), CoroutineScope(Dispatchers.Default))
        val runtime = GenerationRuntime(
            store = store,
            queue = queue,
            service = service,
            worker = worker,
            scheduler = scheduler,
            scope = CoroutineScope(Dispatchers.Default),
            workerBlockMs = config.workerBlockMs,
            resources = emptyList(),
        )
        return Harness(control, runtime)
    }

    private fun applicationTest(block: suspend (HttpClient) -> Unit) = testApplication {
        val h = harness()
        application {
            module(
                Files.createTempDirectory("glosso-gen-admin").toFile(),
                adminToken = adminToken,
                analytics = null,
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

    private fun HttpRequestBuilder.authorized() = header(HttpHeaders.Authorization, "Bearer $adminToken")

    @Test
    fun `an operator can run a cycle and then see the active pool`() = applicationTest { client ->
        val run: GenerationRunResponse = client.post("/v1/admin/generation/run") {
            authorized()
        }.body()
        assertEquals(3, run.messagesEnqueued)

        val status: GenerationStatusResponse = client.get("/v1/admin/generation/status") {
            authorized()
        }.body()
        assertEquals("in-memory", status.queue)
        assertEquals(3, status.jobs["DONE"])

        val pool: ContentPoolResponse = client.get("/v1/admin/content-pool?status=ACTIVE") {
            authorized()
        }.body()
        assertEquals(3, pool.items.size)
        assertTrue(pool.items.all { it.status == ContentStatus.ACTIVE.name })
        assertTrue(pool.items.all { !it.text.isNullOrBlank() && !it.audioUrl.isNullOrBlank() })

        // A second cycle is a no-op: the pool already covers the class.
        val second: GenerationRunResponse = client.post("/v1/admin/generation/run") {
            authorized()
        }.body()
        assertEquals(0, second.jobsCreated)
        assertEquals(0, second.messagesEnqueued)
    }

    @Test
    fun `jobs are listed with their status filter`() = applicationTest { client ->
        client.post("/v1/admin/generation/run") { authorized() }
        val done: GenerationJobsResponse = client.get("/v1/admin/generation/jobs?status=done") {
            authorized()
        }.body()
        assertEquals(3, done.jobs.size)
        assertTrue(done.jobs.all { it.status == "DONE" })

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/v1/admin/generation/jobs?status=nope") { authorized() }.status,
        )
    }

    @Test
    fun `the generation views are admin only`() = applicationTest { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/generation/run").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/generation/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/generation/jobs").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/content-pool").status)
    }
}
