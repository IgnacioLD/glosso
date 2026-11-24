package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ScoreRetryTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(vararg replies: Pair<HttpStatusCode, String>): Pair<GlossoApi, () -> Int> {
        var calls = 0
        val engine = MockEngine {
            val (status, body) = replies[calls++]
            respond(body, status, if (status == HttpStatusCode.ServiceUnavailable) headersOf(HttpHeaders.RetryAfter, "1") else json)
        }
        val scope = object : ContentScope {
            override val language = "en"
            override val accent = "en-GB"
            override val authoredAccent = "en-GB"
            override val uiLanguage = "en"
        }
        val api = GlossoApi({ "http://test" }, engine, null, AssetVersionStore.None, "", { null }, { false }, scope)
        return api to { calls }
    }

    @Test
    fun busyServerIsRetriedOnce() = runTest {
        val (api, calls) = api(
            HttpStatusCode.ServiceUnavailable to """{"error":"busy"}""",
            HttpStatusCode.OK to """{"score":87}""",
        )
        assertEquals(87, api.score(ScoreRequest(text = "hi")).score)
        assertEquals(2, calls())
    }

    @Test
    fun aServerBusyThroughEveryRetryFails() = runTest {
        val busy = HttpStatusCode.ServiceUnavailable to """{"error":"busy"}"""
        val (api, calls) = api(busy, busy, busy)
        assertFailsWith<IllegalStateException> { api.score(ScoreRequest(text = "hi")) }
        assertEquals(3, calls())
    }

    @Test
    fun serverErrorIsAFailureNotAZeroScore() = runTest {
        val (api, _) = api(HttpStatusCode.InternalServerError to """{"error":"boom"}""")
        assertFailsWith<IllegalStateException> { api.score(ScoreRequest(text = "hi")) }
    }

    @Test
    fun unusableSpeechIsNotEvaluated() = runTest {
        val (api, _) = api(HttpStatusCode.UnprocessableEntity to "{}")
        assertFalse(api.score(ScoreRequest(text = "hi")).evaluated)
    }
}
