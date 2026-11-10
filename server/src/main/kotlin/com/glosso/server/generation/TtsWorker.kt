package com.glosso.server.generation

import com.glosso.server.tts.TtsJob
import com.glosso.server.tts.TtsJobProcessor
import com.glosso.server.tts.TtsJobResult
import com.glosso.server.tts.TtsJobStatus
import com.glosso.server.tts.TtsRequest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single generation worker. It consumes `glosso:gen:tts`, and per message it
 * calls the connection point the TTS layer defined (`TtsJobProcessor`), then
 * writes the result back to the pool.
 *
 * It is stateless and idempotent:
 * - a message for a variant that already has audio is acknowledged without
 *   regenerating (the TTS fingerprint would also reuse the clip);
 * - a failure never marks the content ACTIVE: the piece stays out of the pool
 *   until both audio variants exist;
 * - the provider that failed is recorded on the job and in the log;
 * - a transient failure is retried with backoff, a permanent one after
 *   `max_attempts` goes to the DLQ and the job is marked DEAD.
 *
 * `runOnce` is guarded so two calls cannot drain the same queue concurrently
 * inside one process (the design is one worker; scale-out is a later phase).
 */
class TtsWorker(
    private val queue: GenerationQueue,
    private val generationStore: GenerationStore,
    private val processor: TtsJobProcessor,
    private val config: GenerationConfig,
    private val clock: () -> Instant = Instant::now,
    private val log: (String) -> Unit = {},
) {

    private val draining = AtomicBoolean(false)

    /** Drains up to [maxMessages]. Returns how many were handled. */
    fun runOnce(maxMessages: Int = config.maxMessagesPerRun): Int {
        if (!draining.compareAndSet(false, true)) return 0
        try {
            queue.promoteRetries()
            var handled = 0
            while (handled < maxMessages) {
                val block = if (handled == 0) config.workerBlockMs else 0
                val messages = queue.consume(1, block)
                if (messages.isEmpty()) break
                for (message in messages) {
                    handle(message)
                    handled++
                }
            }
            return handled
        } finally {
            draining.set(false)
        }
    }

    /** Handles one message. Never throws: queue/database errors are logged. */
    fun handle(message: GenerationMessage) {
        try {
            val variant = generationStore.variant(message.variantId)
            val job = generationStore.job(message.jobId)
            if (variant == null || job == null) {
                log("Worker: dropping message for missing job=${message.jobId} variant=${message.variantId}")
                queue.ack(message)
                return
            }
            if (variant.hasAudio) {
                // Already produced by an earlier delivery: idempotent no-op.
                if (job.status != JobStatus.DONE) {
                    generationStore.updateJob(job.copy(status = JobStatus.DONE, finishedAt = clock()))
                }
                queue.ack(message)
                return
            }

            generationStore.updateJob(
                job.copy(status = JobStatus.RUNNING, startedAt = job.startedAt ?: clock()),
            )
            val content = generationStore.content(variant.contentId)
            val results = processor.processPair(
                TtsJob(
                    jobId = job.id,
                    request = TtsRequest(
                        text = variant.text,
                        language = content?.language ?: "en",
                        voice = variant.voice.orEmpty(),
                        model = variant.model.orEmpty(),
                    ),
                    contentId = variant.contentId,
                    variantId = variant.id,
                ),
            )
            val normal = results.firstOrNull { it.asset?.speed == "normal" } ?: results.getOrNull(0)
            val slow = results.firstOrNull { it.asset?.speed == "slow" } ?: results.getOrNull(1)
            if (!succeeded(normal) || !succeeded(slow)) {
                fail(message, job, normal, slow)
                return
            }

            generationStore.recordVariantAudio(
                variantId = variant.id,
                audioKey = normal!!.asset!!.objectKey,
                slowAudioKey = slow!!.asset!!.objectKey,
                provider = normal.provider.orEmpty(),
                model = normal.model.orEmpty(),
                voice = normal.voice.orEmpty(),
            )
            generationStore.setContentStatus(variant.contentId, ContentStatus.ACTIVE)
            generationStore.updateJob(
                job.copy(
                    status = JobStatus.DONE,
                    attempts = job.attempts + 1,
                    provider = normal.provider,
                    model = normal.model,
                    finishedAt = clock(),
                    error = null,
                ),
            )
            log("Worker: content ${variant.contentId} ACTIVE (${normal.provider}/${normal.voice})")
            queue.ack(message)
        } catch (e: Exception) {
            // A queue or database error: leave the message unacknowledged so it
            // can be recovered (Redis pending entries), never crash the worker.
            log("Worker: error handling job ${message.jobId}: ${e.message}")
        }
    }

    private fun succeeded(result: TtsJobResult?): Boolean =
        result != null &&
            result.asset != null &&
            result.status != TtsJobStatus.FAILED

    private fun fail(
        message: GenerationMessage,
        job: GenerationJob,
        normal: TtsJobResult?,
        slow: TtsJobResult?,
    ) {
        val attempts = job.attempts + 1
        val provider = normal?.provider ?: slow?.provider
        val reason = listOfNotNull(normal?.errorMessage, slow?.errorMessage).firstOrNull()
            ?: "tts job failed"
        if (attempts < job.maxAttempts) {
            generationStore.updateJob(
                job.copy(status = JobStatus.QUEUED, attempts = attempts, provider = provider, error = reason),
            )
            queue.retry(message, config.retryBackoffMs * attempts)
            log("Worker: job ${job.id} failed (attempt $attempts/${job.maxAttempts}) provider=$provider: $reason")
        } else {
            generationStore.updateJob(
                job.copy(
                    status = JobStatus.DEAD,
                    attempts = attempts,
                    provider = provider,
                    error = reason,
                    finishedAt = clock(),
                ),
            )
            queue.deadLetter(message.copy(attempts = attempts), reason)
            log("Worker: job ${job.id} DEAD after $attempts attempts provider=$provider: $reason")
        }
        queue.ack(message)
    }
}
