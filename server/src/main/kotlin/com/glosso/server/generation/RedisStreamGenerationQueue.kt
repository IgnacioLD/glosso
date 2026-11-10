package com.glosso.server.generation

import java.net.URI
import kotlinx.serialization.json.Json
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.XAddParams
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.params.SetParams

/** Connection details parsed from a `GLOSSO_REDIS_URL`. */
data class RedisConfig(
    val host: String,
    val port: Int,
    val user: String? = null,
    val password: String? = null,
    val database: Int = 0,
    val ssl: Boolean = false,
) {
    /** Never expose the password; use this for logs. */
    override fun toString(): String =
        "RedisConfig(host=$host, port=$port, database=$database, ssl=$ssl, credentials=${if (password.isNullOrEmpty()) "none" else "***"})"

    companion object {
        /**
         * Parses `redis://[user:password@]host:port[/db]` and `rediss://...`.
         * A bare `host:port` is accepted too. Returns null when there is no URL.
         */
        fun parse(url: String?): RedisConfig? {
            val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val normalized = if ("://" in trimmed) trimmed else "redis://$trimmed"
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 6379
            val userInfo = uri.userInfo
            val user = userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() && ':' in userInfo }
            val password = when {
                userInfo == null -> null
                ':' in userInfo -> userInfo.substringAfter(':')
                else -> userInfo
            }
            val database = uri.path?.removePrefix("/")?.toIntOrNull() ?: 0
            return RedisConfig(
                host = host,
                port = port,
                user = user,
                password = password?.takeIf { it.isNotEmpty() },
                database = database,
                ssl = uri.scheme == "rediss",
            )
        }
    }
}

/**
 * Redis Streams implementation of [GenerationQueue]. Production uses this; tests
 * use [InMemoryGenerationQueue] or an embedded Redis.
 *
 * - Stream `glosso:gen:tts`, consumer group `workers-tts`.
 * - `glosso:gen:retry` is a ZSET scored by due time.
 * - `glosso:gen:dlq` is a stream for jobs that exhausted their attempts.
 * - `glosso:job:<hash>` is a `SET NX EX` idempotency lock.
 *
 * Redis is treated as transient: it is never the truth, so a Redis restart only
 * means re-enqueueing from PostgreSQL.
 */
class RedisStreamGenerationQueue(
    private val redis: RedisConfig,
    private val stream: String = DEFAULT_STREAM,
    private val group: String = DEFAULT_GROUP,
    val consumer: String = DEFAULT_CONSUMER,
    private val retryKey: String = DEFAULT_RETRY_KEY,
    private val dlqKey: String = DEFAULT_DLQ_KEY,
) : GenerationQueue {

    override val id: String = "redis"

    private val pool = JedisPool(
        HostAndPort(redis.host, redis.port),
        DefaultJedisClientConfig.builder()
            .apply {
                if (!redis.user.isNullOrEmpty()) user(redis.user)
                if (!redis.password.isNullOrEmpty()) password(redis.password)
                database(redis.database)
                ssl(redis.ssl)
                connectionTimeoutMillis(5_000)
                socketTimeoutMillis(35_000)
                blockingSocketTimeoutMillis(35_000)
            }
            .build(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    init {
        ensureGroup()
    }

    private fun ensureGroup() {
        withJedis { jedis ->
            try {
                jedis.xgroupCreate(stream, group, StreamEntryID.LAST_ENTRY, true)
            } catch (e: JedisDataException) {
                if (e.message?.contains("BUSYGROUP") != true) throw e
            }
        }
    }

    override fun enqueue(message: GenerationMessage) {
        withJedis { jedis ->
            jedis.xadd(
                stream,
                XAddParams.xAddParams().maxLen(STREAM_MAX_LEN).approximateTrimming(),
                fields(message),
            )
        }
    }

    override fun consume(count: Int, blockMs: Long): List<GenerationMessage> {
        val params = XReadGroupParams.xReadGroupParams().count(count)
        if (blockMs > 0) params.block(blockMs.toInt())
        return withJedis { jedis ->
            val result = jedis.xreadGroup(group, consumer, params, mapOf(stream to StreamEntryID.UNRECEIVED_ENTRY))
            result.orEmpty().flatMap { (_, entries) ->
                entries.map { entry -> toMessage(entry.id.toString(), entry.fields) }
            }
        }
    }

    override fun ack(message: GenerationMessage) {
        val id = message.messageId ?: return
        withJedis { jedis -> jedis.xack(stream, group, StreamEntryID(id)) }
    }

    override fun retry(message: GenerationMessage, delayMs: Long) {
        val due = System.currentTimeMillis() + delayMs
        withJedis { jedis ->
            jedis.zadd(
                retryKey,
                due.toDouble(),
                json.encodeToString(GenerationMessage.serializer(), message.copy(messageId = null)),
            )
        }
    }

    override fun promoteRetries(nowEpochMs: Long): Int {
        val due = withJedis { jedis -> jedis.zrangeByScore(retryKey, 0.0, nowEpochMs.toDouble(), 0, 100) }
        var promoted = 0
        for (member in due) {
            val message = runCatching { json.decodeFromString(GenerationMessage.serializer(), member) }.getOrNull() ?: continue
            withJedis { jedis ->
                jedis.xadd(stream, StreamEntryID.NEW_ENTRY, fields(message))
                jedis.zrem(retryKey, member)
            }
            promoted++
        }
        return promoted
    }

    override fun deadLetter(message: GenerationMessage, reason: String) {
        withJedis { jedis ->
            jedis.xadd(
                dlqKey,
                StreamEntryID.NEW_ENTRY,
                fields(message) + mapOf("reason" to reason.take(500)),
            )
        }
    }

    override fun tryLock(key: String, ttlSeconds: Long): Boolean = withJedis { jedis ->
        jedis.set(key, "1", SetParams.setParams().nx().ex(ttlSeconds)) == "OK"
    }

    override fun depth(): Long = withJedis { jedis -> jedis.xlen(stream) }

    override fun pendingCount(): Long = withJedis { jedis ->
        // Jedis' summary builder fails when there is nothing pending; an empty
        // group means zero in flight, which is exactly the answer.
        runCatching { jedis.xpending(stream, group)?.total ?: 0L }.getOrDefault(0L)
    }

    override fun close() = pool.close()

    private fun fields(message: GenerationMessage): Map<String, String> = mapOf(
        "jobId" to message.jobId,
        "variantId" to message.variantId,
        "contentId" to message.contentId,
        "attempts" to message.attempts.toString(),
    )

    private fun toMessage(id: String, fields: Map<String, String>): GenerationMessage = GenerationMessage(
        jobId = fields["jobId"].orEmpty(),
        variantId = fields["variantId"].orEmpty(),
        contentId = fields["contentId"].orEmpty(),
        attempts = fields["attempts"]?.toIntOrNull() ?: 0,
        messageId = id,
    )

    private fun <T> withJedis(block: (Jedis) -> T): T = pool.resource.use(block)

    companion object {
        const val DEFAULT_STREAM = "glosso:gen:tts"
        const val DEFAULT_GROUP = "workers-tts"
        const val DEFAULT_CONSUMER = "worker-1"
        const val DEFAULT_RETRY_KEY = "glosso:gen:retry"
        const val DEFAULT_DLQ_KEY = "glosso:gen:dlq"

        /** Bound the stream; the truth is in PostgreSQL, not in Redis. */
        const val STREAM_MAX_LEN = 10_000L

        /**
         * Builds the queue from the environment, or null when no Redis is
         * configured or it is unreachable. Never throws: generation is optional
         * and must not stop the API.
         */
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            log: (String) -> Unit = {},
        ): RedisStreamGenerationQueue? {
            val config = RedisConfig.parse(env["GLOSSO_REDIS_URL"] ?: env["REDIS_URL"]) ?: run {
                log("Generation queue disabled: GLOSSO_REDIS_URL is not set")
                return null
            }
            return try {
                val queue = RedisStreamGenerationQueue(
                    redis = config,
                    consumer = env["GLOSSO_WORKER_ID"]?.takeIf { it.isNotBlank() } ?: DEFAULT_CONSUMER,
                )
                queue.depth()
                log("Generation queue ready: $config")
                queue
            } catch (e: Exception) {
                log("Generation queue disabled: ${e.message}")
                null
            }
        }
    }
}
