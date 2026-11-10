package com.glosso.server.tts

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CartesiaTtsProviderTest {

    private val pricing = TtsPricing(mapOf("cartesia" to BigDecimal("40.0")))

    private fun provider(
        transport: HttpTransport,
        apiKey: String? = "test-key",
        extra: Map<String, String> = emptyMap(),
        clock: () -> Long = System::currentTimeMillis,
    ) = CartesiaTtsProvider(
        ProviderConfig(
            id = "cartesia",
            apiKey = apiKey,
            model = "sonic-3.6",
            defaultVoice = "voice-1",
            baseUrl = "https://api.cartesia.test",
            extra = extra,
        ),
        transport,
        pricing,
        clock,
    )

    private fun tokenResponse(token: String = "jwt-1") = HttpTransport.Response(
        status = 200,
        body = """{"token":"$token"}""".toByteArray(),
    )

    private fun okResponse(bytes: ByteArray = validAudioBytes()) = HttpTransport.Response(
        status = 200,
        headers = mapOf("Content-Type" to listOf("audio/mpeg")),
        body = bytes,
    )

    private fun wavResponse(bytes: ByteArray) = HttpTransport.Response(
        status = 200,
        headers = mapOf("Content-Type" to listOf("audio/wav")),
        body = bytes,
    )

    /** Routes `/access-token` to a rotating mint and the rest to the TTS queue. */
    private fun transport(
        tokenResponses: List<HttpTransport.Response> = listOf(tokenResponse()),
        ttsResponses: List<HttpTransport.Response>,
    ): FakeHttpTransport {
        val tokens = ArrayDeque(tokenResponses)
        val tts = ArrayDeque(ttsResponses)
        return FakeHttpTransport { request ->
            if (request.url.endsWith("/access-token")) {
                tokens.removeFirstOrNull() ?: error("FakeHttpTransport ran out of token responses")
            } else {
                tts.removeFirstOrNull() ?: error("FakeHttpTransport ran out of tts responses")
            }
        }
    }

    /** Mints a fresh `jwt-N` on every token call, useful for the 401 retry. */
    private fun rotatingTokenTransport(ttsResponses: List<HttpTransport.Response>): FakeHttpTransport {
        val tts = ArrayDeque(ttsResponses)
        var minted = 0
        return FakeHttpTransport { request ->
            if (request.url.endsWith("/access-token")) {
                minted++
                tokenResponse("jwt-$minted")
            } else {
                tts.removeFirstOrNull() ?: error("FakeHttpTransport ran out of tts responses")
            }
        }
    }

    private fun List<HttpTransport.Request>.tokenRequests() = filter { it.url.endsWith("/access-token") }
    private fun List<HttpTransport.Request>.ttsRequests() = filter { it.url.endsWith("/tts/bytes") }

    @Test
    fun `exchanges the api key for an access token and sends the documented request`() {
        val transport = transport(ttsResponses = listOf(okResponse()))
        val result = provider(transport).synthesize(TtsRequest(text = "Hello", language = "en"), 1.0)

        assertEquals("cartesia", result.provider)
        assertEquals("sonic-3.6", result.model)
        assertEquals("voice-1", result.voice)
        assertTrue(result.audio.contentEquals(validAudioBytes()))
        assertEquals(BigDecimal("0.000200"), result.estimatedCostUsd) // 5 chars * 40 / 1e6

        val tokenRequest = transport.requests.tokenRequests().single()
        assertEquals("POST", tokenRequest.method)
        assertEquals("https://api.cartesia.test/access-token", tokenRequest.url)
        assertEquals("Bearer test-key", tokenRequest.headers["Authorization"])
        assertEquals("2026-08-14", tokenRequest.headers["Cartesia-Version"])
        assertNull(tokenRequest.headers["X-API-Key"])
        val tokenBody = Json.parseToJsonElement(tokenRequest.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("true", tokenBody["grants"]!!.jsonObject["tts"]!!.jsonPrimitive.content)
        assertEquals(120, tokenBody["expires_in"]!!.jsonPrimitive.content.toInt())

        val tts = transport.requests.ttsRequests().single()
        assertEquals("POST", tts.method)
        assertEquals("https://api.cartesia.test/tts/bytes", tts.url)
        assertEquals("Bearer jwt-1", tts.headers["Authorization"])
        assertEquals("2026-08-14", tts.headers["Cartesia-Version"])
        assertNull(tts.headers["X-API-Key"])

        val body = Json.parseToJsonElement(tts.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("sonic-3.6", body["model_id"]!!.jsonPrimitive.content)
        assertEquals("Hello", body["transcript"]!!.jsonPrimitive.content)
        assertEquals("voice-1", body["voice"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["output_format"]!!.jsonObject["container"]!!.jsonPrimitive.content)
        assertEquals(1.0, body["generation_config"]!!.jsonObject["speed"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `caches the access token across phrases`() {
        val transport = transport(ttsResponses = listOf(okResponse(), okResponse()))
        val cartesia = provider(transport)

        cartesia.synthesize(TtsRequest(text = "One"), 1.0)
        cartesia.synthesize(TtsRequest(text = "Two"), 1.0)

        assertEquals(1, transport.requests.tokenRequests().size)
        assertEquals(2, transport.requests.ttsRequests().size)
        assertTrue(transport.requests.ttsRequests().all { it.headers["Authorization"] == "Bearer jwt-1" })
    }

    @Test
    fun `re-mints the token once after a 401 and retries`() {
        val transport = rotatingTokenTransport(
            listOf(
                HttpTransport.Response(status = 401, body = """{"message":"Invalid API key"}""".toByteArray()),
                okResponse(),
            ),
        )
        val result = provider(transport).synthesize(TtsRequest(text = "Hello"), 1.0)

        assertEquals("cartesia", result.provider)
        assertEquals(2, transport.requests.tokenRequests().size)
        assertEquals(2, transport.requests.ttsRequests().size)
        assertEquals("Bearer jwt-1", transport.requests.ttsRequests()[0].headers["Authorization"])
        assertEquals("Bearer jwt-2", transport.requests.ttsRequests()[1].headers["Authorization"])
    }

    @Test
    fun `two 401s from tts bytes are a permanent authentication error`() {
        val transport = rotatingTokenTransport(
            listOf(
                HttpTransport.Response(status = 401, body = """{"message":"expired"}""".toByteArray()),
                HttpTransport.Response(status = 401, body = """{"message":"expired"}""".toByteArray()),
            ),
        )
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hi"), 1.0) }

        assertEquals(TtsFailureKind.AUTHENTICATION, error.kind)
        assertFalse(error.kind.isTransient)
        assertEquals(2, transport.requests.tokenRequests().size)
        assertEquals(2, transport.requests.ttsRequests().size)
    }

    @Test
    fun `a 401 from the token endpoint is permanent and skips synthesis`() {
        val transport = transport(
            tokenResponses = listOf(
                HttpTransport.Response(status = 401, body = """{"message":"Invalid API key"}""".toByteArray()),
            ),
            ttsResponses = emptyList(),
        )
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hi"), 1.0) }

        assertEquals(TtsFailureKind.AUTHENTICATION, error.kind)
        assertFalse(error.kind.isTransient)
        assertTrue(transport.requests.ttsRequests().isEmpty())
    }

    @Test
    fun `slow variant sends the slowed factor`() {
        val transport = transport(ttsResponses = listOf(okResponse()))
        val result = provider(transport).synthesize(TtsRequest(text = "Hello", speed = TtsSpeed.SLOW), 0.7)
        val body = Json.parseToJsonElement(transport.requests.ttsRequests().single().body!!.toString(Charsets.UTF_8))
            .jsonObject
        assertEquals(0.7, body["generation_config"]!!.jsonObject["speed"]!!.jsonPrimitive.content.toDouble())
        assertEquals(TtsSpeed.SLOW, result.speed)
    }

    @Test
    fun `429 is transient and reports retry-after`() {
        val transport = transport(
            ttsResponses = listOf(
                HttpTransport.Response(
                    status = 429,
                    headers = mapOf("Retry-After" to listOf("5")),
                    body = """{"message":"slow down"}""".toByteArray(),
                ),
            ),
        )
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hello"), 1.0) }
        assertEquals(TtsFailureKind.RATE_LIMITED, error.kind)
        assertTrue(error.kind.isTransient)
        assertEquals(5L, error.retryAfterSeconds)
    }

    @Test
    fun `5xx is transient`() {
        val transport = transport(ttsResponses = listOf(HttpTransport.Response(status = 503)))
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.SERVER_ERROR, error.kind)
        assertTrue(error.kind.isTransient)
    }

    @Test
    fun `408 is a transient timeout`() {
        val transport = transport(ttsResponses = listOf(HttpTransport.Response(status = 408)))
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.TIMEOUT, error.kind)
        assertTrue(error.kind.isTransient)
    }

    @Test
    fun `400 about the language is a permanent content error`() {
        val transport = transport(
            ttsResponses = listOf(
                HttpTransport.Response(status = 400, body = """{"message":"unsupported language xx"}""".toByteArray()),
            ),
        )
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.UNSUPPORTED_LANGUAGE, error.kind)
        assertFalse(error.kind.isTransient)
    }

    @Test
    fun `blank text fails before any HTTP call`() {
        val transport = transport(ttsResponses = listOf(okResponse()))
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "  "), 1.0) }
        assertEquals(TtsFailureKind.INVALID_TEXT, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `unsupported language fails before any HTTP call`() {
        val transport = transport(ttsResponses = listOf(okResponse()))
        val error = assertFailsWith<TtsException> {
            provider(transport).synthesize(TtsRequest(text = "Hi", language = "klingon"), 1.0)
        }
        assertEquals(TtsFailureKind.UNSUPPORTED_LANGUAGE, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `missing api key is a configuration error`() {
        val transport = transport(ttsResponses = listOf(okResponse()))
        val error = assertFailsWith<TtsException> {
            provider(transport, apiKey = null).synthesize(TtsRequest(text = "Hi"), 1.0)
        }
        assertEquals(TtsFailureKind.CONFIGURATION, error.kind)
    }

    @Test
    fun `ogg output is rejected as unsupported format`() {
        val transport = transport(ttsResponses = listOf(okResponse()))
        val error = assertFailsWith<TtsException> {
            provider(transport).synthesize(
                TtsRequest(text = "Hi", format = AudioFormat(AudioContainer.OGG)),
                1.0,
            )
        }
        assertEquals(TtsFailureKind.UNSUPPORTED_FORMAT, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `wav duration is computed from the payload, not the placeholder header`() {
        // 44_100 Hz * 1 channel * 2 bytes = 88_200 bytes/second -> 1 s.
        val mono = wavBytes(sampleRate = 44_100, channels = 1, bits = 16, dataBytes = 88_200)
        val transport = transport(ttsResponses = listOf(wavResponse(mono)))
        val result = provider(transport).synthesize(
            TtsRequest(text = "Hi", format = AudioFormat(AudioContainer.WAV, sampleRate = 44_100)),
            1.0,
        )
        assertEquals(1_000L, result.durationMs)
    }

    @Test
    fun `wav duration respects stereo and sample rate from the header`() {
        // 24_000 Hz * 2 channels * 2 bytes = 96_000 bytes/second -> 500 ms.
        val stereo = wavBytes(sampleRate = 24_000, channels = 2, bits = 16, dataBytes = 48_000)
        val transport = transport(ttsResponses = listOf(wavResponse(stereo)))
        val result = provider(transport).synthesize(
            TtsRequest(text = "Hi", format = AudioFormat(AudioContainer.WAV, sampleRate = 24_000)),
            1.0,
        )
        assertEquals(500L, result.durationMs)
    }

    @Test
    fun `token settings are read from the provider config`() {
        val transport = transport(ttsResponses = listOf(okResponse()))
        val cartesia = provider(
            transport,
            extra = mapOf(
                "tokenVersion" to "2026-09-01",
                "tokenGrants" to "tts,voices",
                "tokenExpiresIn" to "600",
                "tokenRefreshSkewSeconds" to "120",
            ),
        )
        cartesia.synthesize(TtsRequest(text = "Hi"), 1.0)

        val tokenRequest = transport.requests.tokenRequests().single()
        assertEquals("2026-09-01", tokenRequest.headers["Cartesia-Version"])
        val body = Json.parseToJsonElement(tokenRequest.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("true", body["grants"]!!.jsonObject["tts"]!!.jsonPrimitive.content)
        assertEquals("true", body["grants"]!!.jsonObject["voices"]!!.jsonPrimitive.content)
        assertEquals(600, body["expires_in"]!!.jsonPrimitive.content.toInt())
    }

    /** A PCM WAV whose RIFF/data lengths are placeholders, like Cartesia streams. */
    private fun wavBytes(sampleRate: Int, channels: Int, bits: Int, dataBytes: Int): ByteArray {
        val bytes = ByteArray(44 + dataBytes)
        fun putAscii(offset: Int, value: String) {
            for (i in value.indices) bytes[offset + i] = value[i].code.toByte()
        }
        fun putIntLe(offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        fun putShortLe(offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        val bytesPerSecond = sampleRate * channels * bits / 8
        putAscii(0, "RIFF")
        putIntLe(4, 0x7FFFFFFF)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putIntLe(16, 16)
        putShortLe(20, 1)
        putShortLe(22, channels)
        putIntLe(24, sampleRate)
        putIntLe(28, bytesPerSecond)
        putShortLe(32, channels * bits / 8)
        putShortLe(34, bits)
        putAscii(36, "data")
        putIntLe(40, 0x7FFFFFFF)
        return bytes
    }
}
