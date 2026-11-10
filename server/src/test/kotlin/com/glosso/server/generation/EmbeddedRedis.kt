package com.glosso.server.generation

import java.io.IOException
import java.net.ServerSocket
import redis.embedded.RedisServer

/**
 * Starts an embedded Redis on a free port. The port is free when asked for but
 * released before Redis binds it, so another test of the suite (an embedded
 * Postgres, say) can take it in between: retry on a fresh port instead of
 * failing the test.
 */
internal fun startEmbeddedRedis(attempts: Int = 5): Pair<RedisServer, Int> {
    var last: IOException? = null
    repeat(attempts) {
        val port = ServerSocket(0).use { it.localPort }
        val redis = RedisServer.newRedisServer().port(port).build()
        try {
            redis.start()
            return redis to port
        } catch (e: IOException) {
            last = e
            runCatching { redis.stop() }
        }
    }
    throw IOException("embedded Redis did not start after $attempts ports", last)
}
