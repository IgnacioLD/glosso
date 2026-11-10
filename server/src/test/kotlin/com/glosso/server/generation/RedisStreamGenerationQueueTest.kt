package com.glosso.server.generation

import redis.embedded.RedisServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the real Redis Streams queue against an embedded Redis, so the
 * consumer group, the retry ZSET and the lock are proven, not simulated.
 */
class RedisStreamGenerationQueueTest {

    private lateinit var redis: RedisServer
    private lateinit var queue: RedisStreamGenerationQueue
    private var port: Int = 0

    @BeforeTest
    fun startRedis() {
        val (server, free) = startEmbeddedRedis()
        redis = server
        port = free
        queue = RedisStreamGenerationQueue(RedisConfig(host = "127.0.0.1", port = port))
    }

    @AfterTest
    fun stopRedis() {
        queue.close()
        redis.stop()
    }

    private fun message(jobId: String = "job-1", variantId: String = "variant-1") =
        GenerationMessage(jobId = jobId, variantId = variantId, contentId = "content-1")

    @Test
    fun `enqueue and consume round trip through a consumer group`() {
        queue.enqueue(message())
        val consumed = queue.consume(1, blockMs = 2_000)
        assertEquals(1, consumed.size)
        assertEquals("job-1", consumed.single().jobId)
        assertEquals("variant-1", consumed.single().variantId)
        assertEquals("content-1", consumed.single().contentId)

        queue.ack(consumed.single())
        assertEquals(0, queue.consume(1, blockMs = 100).size)
    }

    @Test
    fun `a consumed but unacked message is not redelivered as new`() {
        queue.enqueue(message())
        val first = queue.consume(1, blockMs = 2_000).single()
        // Without ack, the same consumer reading new messages sees nothing.
        assertTrue(queue.consume(1, blockMs = 50).isEmpty())
        queue.ack(first)
    }

    @Test
    fun `retries are promoted back to the stream when due`() {
        queue.enqueue(message())
        val consumed = queue.consume(1, blockMs = 2_000).single()
        queue.retry(consumed, delayMs = 0)
        queue.ack(consumed)

        val promoted = queue.promoteRetries(nowEpochMs = System.currentTimeMillis() + 1_000)
        assertEquals(1, promoted)
        val again = queue.consume(1, blockMs = 2_000).single()
        assertEquals("job-1", again.jobId)
        queue.ack(again)
    }

    @Test
    fun `a retry in the future is not promoted yet`() {
        queue.enqueue(message())
        val consumed = queue.consume(1, blockMs = 2_000).single()
        queue.retry(consumed, delayMs = 60_000)
        queue.ack(consumed)
        assertEquals(0, queue.promoteRetries())
    }

    @Test
    fun `the idempotency lock is exclusive until its ttl expires`() {
        assertTrue(queue.tryLock("glosso:job:abc", ttlSeconds = 60))
        assertFalse(queue.tryLock("glosso:job:abc", ttlSeconds = 60))
        assertTrue(queue.tryLock("glosso:job:other", ttlSeconds = 60))
    }

    @Test
    fun `depth reflects pending messages`() {
        assertEquals(0, queue.depth())
        queue.enqueue(message("job-a"))
        queue.enqueue(message("job-b"))
        assertEquals(2, queue.depth())
    }

    @Test
    fun `the url parser accepts redis and rediss forms`() {
        assertEquals(
            RedisConfig(host = "redis", port = 6379, password = null, database = 0),
            RedisConfig.parse("redis://redis:6379"),
        )
        assertEquals(
            RedisConfig(host = "host", port = 6380, password = "secret", database = 2),
            RedisConfig.parse("redis://:secret@host:6380/2"),
        )
        assertTrue(RedisConfig.parse("rediss://host:6380")!!.ssl)
        assertEquals(null, RedisConfig.parse(""))
        assertEquals(null, RedisConfig.parse(null))
    }
}
