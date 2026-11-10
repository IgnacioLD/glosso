package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bug this guards: every screen that opens on start asked for the ~900 KB
 * manifest at once, so an empty HTTP cache downloaded it once per screen.
 */
class GlossoApiManifestTest {

    @Test
    fun `concurrent manifest calls share one request and later calls still revalidate`() = runTest {
        val requests = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = MockEngine {
                requests.incrementAndGet()
                gate.await()
                respond(
                    """{"assetVersion":"v1"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
            contentCache = null,
        )

        val calls = List(4) { async { api.manifest() } }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (requests.get() == 0) Thread.sleep(5) }
            Thread.sleep(200) // let the other callers send theirs, if they would
        }
        gate.complete(Unit)

        assertEquals(List(4) { "v1" }, calls.awaitAll().map { it.assetVersion })
        assertEquals(1, requests.get())

        api.manifest()
        assertEquals("a later open still revalidates", 2, requests.get())
    }
}
