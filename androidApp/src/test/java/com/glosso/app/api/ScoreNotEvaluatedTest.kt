package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScoreNotEvaluatedTest {

    @Test
    fun `no usable speech is not evaluated, never a zero`() = runTest {
        // The deployed server answers 422 for a recording with no usable speech.
        // Its body would otherwise deserialize into an all-defaults feedback, a
        // bogus 0/100. It must surface as a not-evaluated attempt instead.
        val engine = MockEngine {
            respond(
                """{"error":"No usable speech detected in the recording"}""",
                HttpStatusCode.UnprocessableEntity,
            )
        }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null)

        val feedback = api.score(ScoreRequest("hello"))

        assertFalse("a silent take must not be scored", feedback.evaluated)
        assertEquals(0, feedback.score)
    }
}
