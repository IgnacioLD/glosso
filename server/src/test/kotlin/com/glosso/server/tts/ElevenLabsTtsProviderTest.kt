package com.glosso.server.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ElevenLabsTtsProviderTest {

    private val pricing = TtsPricing(emptyMap())

    private fun provider(transport: HttpTransport, apiKey: String? = "test-key") = ElevenLabsTtsProvider(
        ProviderConfig(
            id = "elevenlabs",
            apiKey = apiKey,
            model = "eleven_multilingual_v2",
            defaultVoice = "voice-1",
            baseUrl = "https://api.elevenlabs.test",
        ),
        transport,
        pricing,
    )

    private fun okResponse(bytes: ByteArray = validAudioBytes()) = HttpTransport.Response(
        status = 200,
        headers = mapOf("Content-Type" to listOf("audio/mpeg")),
        body = bytes,
    )

    @Test
    fun `sends the documented request and parses audio`() {
        val transport = FakeHttpTransport.responding(okResponse())
        val result = provider(transport).synthesize(TtsRequest(text = "Hello", language = "en"), 1.0)

        assertEquals("elevenlabs", result.provider)
        assertEquals("eleven_multilingual_v2", result.model)
        assertEquals("voice-1", result.voice)
        assertTrue(result.audio.contentEquals(validAudioBytes()))

        val request = transport.requests.single()
        assertEquals("https://api.elevenlabs.test/v1/text-to-speech/voice-1?output_format=mp3_44100_128", request.url)
        assertEquals("test-key", request.headers["xi-api-key"])

        val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("Hello", body["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_multilingual_v2", body["model_id"]!!.jsonPrimitive.content)
        assertEquals(1.0, body["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `speed is clamped into the vendor range`() {
        val transport = FakeHttpTransport.responding(okResponse())
        provider(transport).synthesize(TtsRequest(text = "Hello", speed = TtsSpeed.SLOW), 0.5)
        val body = Json.parseToJsonElement(transport.requests.single().body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(0.7, body["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `401 is permanent`() {
        val transport = FakeHttpTransport.responding(HttpTransport.Response(status = 401))
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.AUTHENTICATION, error.kind)
        assertFalse(error.kind.isTransient)
    }

    @Test
    fun `500 is transient`() {
        val transport = FakeHttpTransport.responding(HttpTransport.Response(status = 500))
        val error = assertFailsWith<TtsException> { provider(transport).synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.SERVER_ERROR, error.kind)
        assertTrue(error.kind.isTransient)
    }

    @Test
    fun `a voice id with path or query characters is rejected before any HTTP call`() {
        val transport = FakeHttpTransport.responding(okResponse())
        val error = assertFailsWith<TtsException> {
            provider(transport).synthesize(
                TtsRequest(text = "Hi", voice = "../../admin?x=1"),
                1.0,
            )
        }
        assertEquals(TtsFailureKind.UNSUPPORTED_VOICE, error.kind)
        assertTrue(transport.requests.isEmpty(), "a malformed voice must not reach the vendor URL")
    }

    @Test
    fun `non-mp3 output is rejected before any HTTP call`() {
        val transport = FakeHttpTransport.responding(okResponse())
        val error = assertFailsWith<TtsException> {
            provider(transport).synthesize(
                TtsRequest(text = "Hi", format = AudioFormat(AudioContainer.WAV)),
                1.0,
            )
        }
        assertEquals(TtsFailureKind.UNSUPPORTED_FORMAT, error.kind)
        assertTrue(transport.requests.isEmpty())
    }
}
