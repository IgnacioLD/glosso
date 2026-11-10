package com.glosso.server.generation

import com.glosso.server.tts.FakeTtsProvider
import com.glosso.server.tts.FlakyTtsProvider
import com.glosso.server.tts.TtsException
import com.glosso.server.tts.TtsFailureKind
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `glosso:gen:tts` -> `TtsJobProcessor` -> pool step, over a real TtsService
 * with a fake provider. It proves the worker is idempotent and that a failure
 * never leaves a half-active piece.
 */
class TtsWorkerTest {

    private val store = InMemoryGenerationStore()
    private val queue = InMemoryGenerationQueue()
    private val config = GenerationConfig(
        provider = "fake",
        model = "fake-model",
        maxAttempts = 3,
        retryBackoffMs = 0,
        workerBlockMs = 0,
    )

    private data class Seeded(
        val contentId: String,
        val variantId: String,
        val job: GenerationJob,
    )

    private fun seed(maxAttempts: Int = 3): Seeded {
        val contentId = UUID.randomUUID().toString()
        store.insertContent(
            ContentPiece(
                id = contentId,
                language = "en",
                cefr = "A1",
                targetPhoneme = "θ",
                status = ContentStatus.APPROVED,
            ),
        )
        val variantId = UUID.randomUUID().toString()
        store.insertVariant(
            ContentVariant(
                id = variantId,
                contentId = contentId,
                variantHash = "hash-${UUID.randomUUID()}",
                text = "I said think.",
                status = ContentStatus.APPROVED,
            ),
        )
        val job = GenerationJob(
            id = UUID.randomUUID().toString(),
            jobHash = "job-${UUID.randomUUID()}",
            contentId = contentId,
            variantId = variantId,
            maxAttempts = maxAttempts,
        )
        store.insertJobIfAbsent(job)
        queue.enqueue(GenerationMessage(job.id, variantId, contentId))
        return Seeded(contentId, variantId, job)
    }

    private fun worker(processor: com.glosso.server.tts.TtsJobProcessor) =
        TtsWorker(queue, store, processor, config)

    @Test
    fun `a successful job stores both audio variants and activates the content`() {
        val seeded = seed()
        val provider = com.glosso.server.tts.echoingFakeProvider()
        val harness = ttsHarness(provider)
        val worker = worker(harness.processor)

        assertEquals(1, worker.runOnce())

        val variant = store.variant(seeded.variantId)!!
        assertTrue(variant.hasAudio)
        assertNotNull(variant.audioKey)
        assertNotNull(variant.slowAudioKey)
        assertTrue(variant.audioKey != variant.slowAudioKey, "normal and slow must be distinct assets")
        assertTrue(harness.objectStore.exists(variant.audioKey!!))
        assertTrue(harness.objectStore.exists(variant.slowAudioKey!!))
        assertEquals(ContentStatus.ACTIVE, store.content(seeded.contentId)!!.status)

        val job = store.job(seeded.job.id)!!
        assertEquals(JobStatus.DONE, job.status)
        assertEquals(1, job.attempts)
        assertEquals("fake", job.provider)
        assertEquals(0, queue.depth())
    }

    @Test
    fun `redelivering a finished message never regenerates`() {
        val seeded = seed()
        val provider = com.glosso.server.tts.echoingFakeProvider()
        val harness = ttsHarness(provider)
        val worker = worker(harness.processor)

        worker.runOnce()
        val callsAfterFirst = provider.calls
        queue.enqueue(GenerationMessage(seeded.job.id, seeded.variantId, seeded.contentId))
        worker.runOnce()

        assertEquals(callsAfterFirst, provider.calls)
        assertEquals(1, store.variants.size)
        assertEquals(JobStatus.DONE, store.job(seeded.job.id)!!.status)
    }

    @Test
    fun `a permanent failure is retried and then dead-lettered, and never activates content`() {
        val seeded = seed(maxAttempts = 2)
        val provider = FakeTtsProvider("fake") { _, _ ->
            throw TtsException(TtsFailureKind.INVALID_TEXT, "bad text")
        }
        val worker = worker(ttsHarness(provider).processor)

        worker.runOnce() // attempt 1: transient? no, permanent -> retry scheduled
        assertEquals(JobStatus.QUEUED, store.job(seeded.job.id)!!.status)
        assertEquals(1, store.job(seeded.job.id)!!.attempts)
        assertFalse(store.variant(seeded.variantId)!!.hasAudio)
        assertFalse(store.content(seeded.contentId)!!.status == ContentStatus.ACTIVE)

        worker.runOnce() // attempt 2: max reached -> DEAD
        val job = store.job(seeded.job.id)!!
        assertEquals(JobStatus.DEAD, job.status)
        assertEquals(2, job.attempts)
        assertEquals("fake", job.provider)
        assertTrue(job.error!!.contains("fake"))
        assertFalse(store.variant(seeded.variantId)!!.hasAudio, "content must not be half-active")
    }

    @Test
    fun `a transient failure is retried and eventually succeeds without duplicating`() {
        val seeded = seed(maxAttempts = 3)
        val provider = FlakyTtsProvider("fake", TtsFailureKind.SERVER_ERROR, failures = 1)
        // No provider-internal retries here: the worker's own retry is what we test.
        val harness = ttsHarness(provider, config = com.glosso.server.tts.testConfig(maxRetries = 0))
        val worker = worker(harness.processor)

        worker.runOnce() // fails once, schedules the retry
        assertFalse(store.variant(seeded.variantId)!!.hasAudio)

        worker.runOnce() // promotes the due retry and succeeds
        val variant = store.variant(seeded.variantId)!!
        assertTrue(variant.hasAudio)
        assertEquals(ContentStatus.ACTIVE, store.content(seeded.contentId)!!.status)
        val job = store.job(seeded.job.id)!!
        assertEquals(JobStatus.DONE, job.status)
        assertEquals(2, job.attempts)
        assertEquals(1, store.variants.size, "no duplicate variant")
        assertEquals(1, store.contents.size, "no duplicate content")
    }

    @Test
    fun `a message for a missing variant is dropped without crashing`() {
        val worker = worker(ttsHarness(FakeTtsProvider("fake") { _, _ -> com.glosso.server.tts.fakeResult() }).processor)
        queue.enqueue(GenerationMessage("missing-job", "missing-variant", "missing-content"))
        assertEquals(1, worker.runOnce())
        assertEquals(0, queue.depth())
    }
}
