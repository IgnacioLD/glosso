package com.glosso.server.generation

import com.glosso.server.tts.FakeTtsProvider
import com.glosso.server.tts.TtsException
import com.glosso.server.tts.TtsFailureKind
import com.glosso.server.tts.fakeResult
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import redis.embedded.RedisServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phase-2 flow end to end, with no mocks in the middle: a real PostgreSQL
 * (embedded), a real Redis Streams queue (embedded), the real generation service
 * and worker, and the real TTS service with a fake provider (a provider is the
 * only thing a test may fake).
 *
 * Needs -> Need Class -> Gap -> generation job -> `glosso:gen:tts` -> worker ->
 * TTS -> audio validation -> object store -> Content ACTIVE in the pool.
 */
class GenerationQueueE2ETest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var redis: RedisServer
    private lateinit var queue: RedisStreamGenerationQueue
    private lateinit var control: JdbcControlPlaneStore
    private lateinit var store: JdbcGenerationStore

    private val pool = PoolConfig.of(2, 3, 5)
    private val config = GenerationConfig(
        provider = "fake",
        model = "fake-model",
        maxAttempts = 2,
        retryBackoffMs = 0,
        workerBlockMs = 0,
    )

    @BeforeTest
    fun startInfrastructure() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        SchemaMigrator.migrate(dataSource)

        val (server, port) = startEmbeddedRedis()
        redis = server
        queue = RedisStreamGenerationQueue(RedisConfig(host = "127.0.0.1", port = port))

        control = JdbcControlPlaneStore(dataSource)
        store = JdbcGenerationStore(dataSource)
    }

    @AfterTest
    fun stopInfrastructure() {
        queue.close()
        redis.stop()
        postgres.close()
    }

    private fun catalog() = CompositeContentCatalog.of(EmptyContentCatalog, CompositeContentCatalog.PoolCatalog(store))

    private fun seedWeakSound(deviceId: String, phoneme: String = "θ", pattern: String = "en.th.stopping.t") {
        val profiles = LearnerProfileService(control)
        val needs = NeedService(control, NeedConfig.DEFAULT)
        val synced = profiles.sync(
            ProfileSyncInput(
                deviceId = deviceId,
                language = "en",
                cefr = "A1",
                pronunciationScore = 58,
                phonemes = listOf(
                    PhonemeSnapshot(
                        phoneme = phoneme,
                        score = 43,
                        confidence = 0.8f,
                        errorPattern = pattern,
                        replacementPhoneme = "t",
                        position = "initial",
                        attempts = 5,
                        successfulAttempts = 0,
                        trend = "declining",
                    ),
                ),
            ),
        )
        needs.deriveAndStore(synced.userId, "en")
    }

    @Test
    fun `a demand gap becomes an ACTIVE piece in the pool, idempotently`() {
        seedWeakSound("device-1")
        val demand = DemandService(control, catalog(), pool)
        val tts = ttsHarness(FakeTtsProvider("fake") { _, _ -> fakeResult(provider = "fake") })
        val generation = GenerationService(control, store, queue, TemplateCandidateSource(), pool, config)
        val worker = TtsWorker(queue, store, tts.processor, config)

        // 1. Aggregate: one Need Class, below minimum.
        val snapshot = demand.aggregate()
        assertEquals(1, snapshot.needClasses)
        assertEquals(1, snapshot.belowMinimum)
        val needClass = demand.needClasses().single()
        assertEquals(0, needClass.availableEffective)
        assertEquals(GapState.BELOW_MIN, needClass.gapState)

        // 2. Gap -> jobs -> queue.
        val run = generation.enqueueGaps()
        assertEquals(3, run.jobsCreated)
        assertEquals(3, run.messagesEnqueued)
        assertEquals(3, queue.depth())
        val queued = store.jobs(JobStatus.QUEUED)
        assertEquals(3, queued.size)

        // 3. Queue -> worker -> TTS -> validation -> object store -> ACTIVE.
        assertEquals(3, worker.runOnce())
        assertEquals(0, queue.pendingCount())
        assertTrue(queue.consume(1, blockMs = 50).isEmpty(), "the queue must be drained")
        val active = store.contentPool(language = "en", status = ContentStatus.ACTIVE)
        assertEquals(3, active.size)
        for (piece in active) {
            val variant = store.variantsForContent(piece.id).single()
            assertTrue(variant.hasAudio, "every active piece must have both audio variants")
            assertNotNull(variant.audioKey)
            assertNotNull(variant.slowAudioKey)
            assertTrue(tts.objectStore.exists(variant.audioKey!!))
            assertTrue(tts.objectStore.exists(variant.slowAudioKey!!))
            assertEquals("fake", variant.provider)
        }
        assertTrue(store.jobs().all { it.status == JobStatus.DONE })

        // 4. The next aggregation counts the pool: the gap is closed.
        val after = demand.aggregate()
        assertEquals(1, after.needClasses)
        val measured = demand.needClasses().single()
        assertEquals(3, measured.availableEffective)
        assertEquals(0, measured.gap)
        assertEquals(GapState.OK, measured.gapState)

        // 5. Running the cycle and the worker again changes nothing.
        val secondRun = generation.enqueueGaps()
        assertEquals(0, secondRun.jobsCreated)
        assertEquals(0, secondRun.messagesEnqueued)
        assertEquals(0, worker.runOnce())
        assertEquals(3, store.contentPool(status = ContentStatus.ACTIVE).size)
        assertEquals(3, store.jobs().size)
    }

    @Test
    fun `a dead job records the failing provider and leaves no half-active content`() {
        seedWeakSound("device-2", phoneme = "ɹ", pattern = "en.r.approximant")
        val demand = DemandService(control, catalog(), pool)
        val failing = FakeTtsProvider("fake") { _, _ ->
            throw TtsException(TtsFailureKind.INVALID_TEXT, "synthetic failure")
        }
        val tts = ttsHarness(failing)
        val generation = GenerationService(control, store, queue, TemplateCandidateSource(), pool, config)
        val worker = TtsWorker(queue, store, tts.processor, config)

        demand.aggregate()
        assertEquals(3, generation.enqueueGaps().messagesEnqueued)

        worker.runOnce() // attempt 1 -> retry
        worker.runOnce() // attempt 2 -> DEAD

        assertEquals(0, queue.pendingCount())
        assertTrue(queue.consume(1, blockMs = 50).isEmpty(), "the queue must be drained")
        val jobs = store.jobs()
        assertEquals(3, jobs.size)
        assertTrue(jobs.all { it.status == JobStatus.DEAD }, "jobs=${jobs.map { it.status }}")
        assertTrue(jobs.all { it.provider == "fake" }, "the failing provider must be recorded")
        assertTrue(jobs.all { !it.error.isNullOrBlank() })

        assertEquals(0, store.contentPool(status = ContentStatus.ACTIVE).size)
        assertTrue(store.contentPool(status = ContentStatus.APPROVED).isNotEmpty())
        assertTrue(store.contentPool().none { it.status == ContentStatus.ACTIVE })
    }
}
