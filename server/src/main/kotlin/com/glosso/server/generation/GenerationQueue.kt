package com.glosso.server.generation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

/**
 * One unit of queued work: the job and the content variant it must synthesize.
 * The message is small and carries ids only; the truth is in PostgreSQL, so a
 * lost Redis message only means re-enqueuing, never losing content.
 */
@Serializable
data class GenerationMessage(
    val jobId: String,
    val variantId: String,
    val contentId: String,
    val attempts: Int = 0,
    /** Set by the queue when the message is consumed; null when enqueued. */
    val messageId: String? = null,
)

/**
 * The work queue. Redis Streams in production, in-memory in tests. Redis is
 * infrastructure only: it is never the source of truth (that is
 * `generation_jobs`), so the interface is small and the worker is stateless.
 *
 * Names fixed by `docs/CONTENT_GENERATION_ARCHITECTURE.md`:
 * `glosso:gen:tts` (stream), `workers-tts` (consumer group), `glosso:gen:retry`
 * (ZSET), `glosso:gen:dlq` (stream) and `glosso:job:<hash>` (lock).
 */
interface GenerationQueue : AutoCloseable {

    /** Stable id, e.g. `redis`, `in-memory`. */
    val id: String

    fun enqueue(message: GenerationMessage)

    /** Reads up to [count] new messages, waiting up to [blockMs] when empty. */
    fun consume(count: Int = 1, blockMs: Long = 0): List<GenerationMessage>

    fun ack(message: GenerationMessage)

    /** Schedules [message] for a later retry, then acknowledges it. */
    fun retry(message: GenerationMessage, delayMs: Long)

    /**
     * Moves every retry whose due time has passed back to the stream. Returns
     * how many were promoted.
     */
    fun promoteRetries(nowEpochMs: Long = System.currentTimeMillis()): Int

    fun deadLetter(message: GenerationMessage, reason: String)

    /** Best-effort distributed lock; false when it is already held. */
    fun tryLock(key: String, ttlSeconds: Long): Boolean

    /** Approximate backlog: entries in the stream (Redis) or waiting (memory). */
    fun depth(): Long

    /** Messages delivered but not yet acknowledged (in flight). */
    fun pendingCount(): Long

    override fun close() {}
}

/**
 * In-memory queue with the same semantics: FIFO, retries due by time, and locks.
 * Messages are removed on consume (auto-ack), which is exactly what the worker
 * needs to be idempotent: the database, not the queue, decides what is done.
 */
class InMemoryGenerationQueue : GenerationQueue {

    override val id: String = "in-memory"

    private val queue = LinkedBlockingQueue<GenerationMessage>()
    private val retries = java.util.PriorityQueue<Pair<Long, GenerationMessage>>(compareBy { it.first })
    private val locks = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong(0)

    override fun enqueue(message: GenerationMessage) {
        queue.put(message.copy(messageId = "mem-${sequence.incrementAndGet()}"))
    }

    override fun consume(count: Int, blockMs: Long): List<GenerationMessage> {
        val messages = ArrayList<GenerationMessage>()
        val first = if (blockMs > 0) queue.poll(blockMs, TimeUnit.MILLISECONDS) else queue.poll()
        if (first == null) return emptyList()
        messages += first
        while (messages.size < count) {
            messages += queue.poll() ?: break
        }
        return messages
    }

    override fun ack(message: GenerationMessage) = Unit

    @Synchronized
    override fun retry(message: GenerationMessage, delayMs: Long) {
        retries += (System.currentTimeMillis() + delayMs) to message.copy(messageId = null)
    }

    @Synchronized
    override fun promoteRetries(nowEpochMs: Long): Int {
        var promoted = 0
        while (retries.isNotEmpty() && retries.peek().first <= nowEpochMs) {
            val (_, message) = retries.poll()
            enqueue(message)
            promoted++
        }
        return promoted
    }

    override fun deadLetter(message: GenerationMessage, reason: String) {
        // Kept for inspection by tests; nothing else to do.
    }

    override fun tryLock(key: String, ttlSeconds: Long): Boolean {
        val now = System.currentTimeMillis()
        val expiry = now + ttlSeconds * 1_000
        var acquired = false
        locks.compute(key) { _, current ->
            if (current == null || current <= now) {
                acquired = true
                expiry
            } else {
                current
            }
        }
        return acquired
    }

    override fun depth(): Long = queue.size.toLong() + retries.size

    /** Messages are removed on consume, so nothing stays in flight. */
    override fun pendingCount(): Long = 0

    @Synchronized
    fun pendingRetries(): Int = retries.size
}
