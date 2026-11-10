package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading vocabulary is the WORD bank of the story's theme; this pins the
 * request the reader relies on.
 */
class GlossoApiWordsTest {

    @Test
    fun `words are fetched for the given theme`() = runTest {
        var path: String? = null
        var theme: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            theme = request.url.parameters["theme"]
            respond(
                """[{"id":"w1","lemma":"cup","ipa":"kʌp","translations":{"es":"taza"}}]""",
                headers = json(),
            )
        }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null)

        val words = api.words(theme = "family")

        assertEquals("/v1/content/WORD", path)
        assertEquals("family", theme)
        assertEquals("cup", words.single().lemma)
        assertEquals("taza", words.single().translations["es"])
    }

    @Test
    fun `an empty word bank is not an error`() = runTest {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, headers = json()) }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null)

        assertTrue(api.words(theme = "work").isEmpty())
    }

    @Test
    fun `wordsByLemmas asks for the lemmas it wants`() = runTest {
        var lemmas: String? = null
        var limit: String? = null
        val engine = MockEngine { request ->
            lemmas = request.url.parameters["lemmas"]
            limit = request.url.parameters["limit"]
            respond(
                """[{"id":"w.think","lemma":"think","ipa":"θɪŋk"},
                    {"id":"w.sink","lemma":"sink","ipa":"sɪŋk"}]""",
                headers = json(),
            )
        }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null)

        // Duplicates and case are removed before the request goes out.
        val words = api.wordsByLemmas(listOf("Think", "sink", "think"))

        assertEquals("think,sink", lemmas)
        assertEquals("2", limit)
        assertEquals(listOf("think", "sink"), words.map { it.lemma })
    }

    @Test
    fun `a server that ignores the lemma filter is filtered on the client`() = runTest {
        // An older deployment ignores `lemmas` and answers with a page of the
        // WORD bank: the extras must never reach the caller.
        val engine = MockEngine {
            respond(
                """[{"id":"w.gate","lemma":"gate"},{"id":"w.think","lemma":"think"},
                    {"id":"w.cat","lemma":"cat"}]""",
                headers = json(),
            )
        }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null)

        val words = api.wordsByLemmas(listOf("think", "sink"))

        assertEquals(listOf("think"), words.map { it.lemma })
    }

    @Test
    fun `wordsByLemmas with no lemma does not call the server`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("[]", HttpStatusCode.OK, headers = json())
        }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null)

        assertTrue(api.wordsByLemmas(listOf("  ")).isEmpty())
        assertEquals(0, calls)
    }

    private fun json(): Headers = Headers.build {
        append(HttpHeaders.ContentType, "application/json")
    }
}
