package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scoring oracle's shared credential. A build without a key must be
 * byte-identical to the old client (no header); a build with one must send it.
 */
class GlossoApiScoreAuthTest {

    private fun engineCapturingKey(seen: (String?) -> Unit) = MockEngine { request ->
        seen(request.headers["X-Api-Key"])
        // 422 short-circuits score() before it parses a body; the assertion is
        // about the request, not the response.
        respond("{}", HttpStatusCode.UnprocessableEntity)
    }

    @Test
    fun `sends the api key header when the build carries one`() = runTest {
        var seen: String? = null
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engineCapturingKey { seen = it },
            contentCache = null,
            apiKey = "s3cret",
        )

        api.score(ScoreRequest("hello"))

        assertEquals("s3cret", seen)
    }

    @Test
    fun `sends no api key header when the build has none`() = runTest {
        var seen: String? = null
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = engineCapturingKey { seen = it },
            contentCache = null,
            apiKey = "",
        )

        api.score(ScoreRequest("hello"))

        assertNull("an empty key must not send the header", seen)
    }
}
