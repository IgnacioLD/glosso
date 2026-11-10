package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage statistics contract with the server: events go as one batch, the
 * erase is a DELETE, and a learner who turned statistics off says so on every
 * request, so the server skips its own score event too.
 */
class GlossoApiAnalyticsTest {

    private class Seen {
        var method: HttpMethod? = null
        var path: String? = null
        var analytics: String? = null
        var body: String? = null
    }

    private fun api(seen: Seen, enabled: Boolean) = GlossoApi(
        baseUrl = "http://test",
        engine = MockEngine { request ->
            seen.method = request.method
            seen.path = request.url.encodedPath
            seen.analytics = request.headers["X-Analytics"]
            seen.body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            respond("{}", HttpStatusCode.Accepted)
        },
        contentCache = null,
        apiKey = "",
        deviceIdProvider = { "device-a" },
        analyticsEnabled = { enabled },
    )

    @Test
    fun `events are posted as one batch`() = runTest {
        val seen = Seen()

        val status = api(seen, enabled = true).sendEvents(listOf(ClientEvent(name = "app_open", occurredAt = 5)), sentAt = 9)

        assertEquals(202, status)
        assertEquals(HttpMethod.Post, seen.method)
        assertEquals("/v1/events", seen.path)
        assertTrue(seen.body.orEmpty(), seen.body.orEmpty().contains("\"events\":[{\"name\":\"app_open\""))
        assertTrue(seen.body.orEmpty(), seen.body.orEmpty().contains("\"sentAt\":9"))
        assertNull(seen.analytics)
    }

    @Test
    fun `turned off, every request carries the opt-out`() = runTest {
        val seen = Seen()

        runCatching { api(seen, enabled = false).score(ScoreRequest("hello")) }

        assertEquals("off", seen.analytics)
    }

    @Test
    fun `erasing is a delete of this device's events`() = runTest {
        val seen = Seen()

        assertTrue(api(seen, enabled = true).deleteEvents())
        assertEquals(HttpMethod.Delete, seen.method)
        assertEquals("/v1/events", seen.path)
    }
}
