package com.glosso.server.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsProviderRouterTest {

    @Test
    fun `retries a transient failure then succeeds`() {
        val primary = FlakyTtsProvider("cartesia", TtsFailureKind.SERVER_ERROR, failures = 2)
        val router = TtsProviderRouter(listOf(primary), maxRetries = 2, maxConcurrency = 1, sleeper = {})
        val result = router.synthesize(TtsRequest(text = "Hi"), 1.0)
        assertEquals("cartesia", result.provider)
        assertEquals(3, primary.calls)
        assertEquals("3", result.metadata["attempts"])
        assertEquals("false", result.metadata["fallback"])
    }

    @Test
    fun `backoff is exponential`() {
        val delays = mutableListOf<Long>()
        val primary = FlakyTtsProvider("cartesia", TtsFailureKind.TIMEOUT, failures = 2)
        val router = TtsProviderRouter(listOf(primary), maxRetries = 2, maxConcurrency = 1, sleeper = { delays.add(it) })
        router.synthesize(TtsRequest(text = "Hi"), 1.0)
        assertEquals(listOf(500L, 1_000L), delays)
    }

    @Test
    fun `retry-after overrides the backoff`() {
        val delays = mutableListOf<Long>()
        val primary = FakeTtsProvider("cartesia") { _, _ ->
            throw TtsException(TtsFailureKind.RATE_LIMITED, "429", retryAfterSeconds = 3)
        }
        val router = TtsProviderRouter(listOf(primary), maxRetries = 1, maxConcurrency = 1, sleeper = { delays.add(it) })
        assertFailsWith<TtsException> { router.synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(listOf(3_000L), delays)
    }

    @Test
    fun `permanent failure is not retried and never falls back`() {
        val primary = FakeTtsProvider("cartesia") { _, _ -> throw TtsException(TtsFailureKind.INVALID_TEXT, "bad") }
        val fallback = FlakyTtsProvider("elevenlabs", TtsFailureKind.SERVER_ERROR, failures = 0)
        val router = TtsProviderRouter(listOf(primary, fallback), maxRetries = 2, maxConcurrency = 1, sleeper = {})
        val error = assertFailsWith<TtsException> { router.synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.INVALID_TEXT, error.kind)
        assertEquals(1, primary.calls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `transient exhaustion falls back and records the provider that produced audio`() {
        val primary = FlakyTtsProvider("cartesia", TtsFailureKind.SERVER_ERROR, failures = 99)
        val fallback = FlakyTtsProvider("elevenlabs", TtsFailureKind.SERVER_ERROR, failures = 0)
        val observations = RecordingTtsObservability()
        val router = TtsProviderRouter(
            listOf(primary, fallback),
            maxRetries = 1,
            maxConcurrency = 1,
            observability = observations,
            sleeper = {},
        )
        val result = router.synthesize(TtsRequest(text = "Hi"), 1.0, jobId = "job-1")
        assertEquals("elevenlabs", result.provider)
        assertEquals("true", result.metadata["fallback"])
        assertEquals(2, primary.calls)
        assertTrue(observations.phases().contains("provider_fallback"))
    }

    @Test
    fun `an IOException is transient and falls back`() {
        val primary = FakeTtsProvider("cartesia") { _, _ -> throw java.io.IOException("connection reset") }
        val fallback = FakeTtsProvider("elevenlabs") { _, _ -> fakeResult(provider = "elevenlabs") }
        val router = TtsProviderRouter(listOf(primary, fallback), maxRetries = 1, maxConcurrency = 1, sleeper = {})
        val result = router.synthesize(TtsRequest(text = "Hi"), 1.0, jobId = "job-1")
        assertEquals("elevenlabs", result.provider)
        assertEquals("true", result.metadata["fallback"])
        assertEquals(2, primary.calls)
    }

    @Test
    fun `an unexpected exception is wrapped and tagged with the provider`() {
        val primary = FakeTtsProvider("cartesia") { _, _ -> throw IllegalStateException("bug") }
        val fallback = FakeTtsProvider("elevenlabs") { _, _ -> fakeResult(provider = "elevenlabs") }
        val router = TtsProviderRouter(listOf(primary, fallback), maxRetries = 2, maxConcurrency = 1, sleeper = {})
        val error = assertFailsWith<TtsException> { router.synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.UNKNOWN, error.kind)
        assertEquals("cartesia", error.provider)
        assertTrue(error.message!!.contains("cartesia"))
        assertEquals(1, primary.calls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `a permanent provider error carries the provider`() {
        val primary = FakeTtsProvider("cartesia") { _, _ -> throw TtsException(TtsFailureKind.INVALID_TEXT, "bad") }
        val router = TtsProviderRouter(listOf(primary), maxRetries = 2, maxConcurrency = 1, sleeper = {})
        val error = assertFailsWith<TtsException> { router.synthesize(TtsRequest(text = "Hi"), 1.0) }
        assertEquals(TtsFailureKind.INVALID_TEXT, error.kind)
        assertEquals("cartesia", error.provider)
    }

    @Test
    fun `candidates resolve model and voice per provider`() {
        val a = FlakyTtsProvider("cartesia", TtsFailureKind.SERVER_ERROR, failures = 0)
        val b = FlakyTtsProvider("elevenlabs", TtsFailureKind.SERVER_ERROR, failures = 0)
        val router = TtsProviderRouter(listOf(a, b), maxRetries = 0, maxConcurrency = 1, sleeper = {})
        val candidates = router.candidates(TtsRequest(text = "Hi"), 0.7)
        assertEquals("flaky-model", candidates[0].request.model)
        assertEquals("flaky-voice", candidates[0].request.voice)
        assertEquals(0.7, candidates[0].speedFactor)
        assertFalse(candidates[0].request.voice.isBlank())
    }

    @Test
    fun `explicit voice wins, then palette, then provider default`() {
        val provider = FlakyTtsProvider("cartesia", TtsFailureKind.SERVER_ERROR, failures = 0)
        val palette = VoicePalette(defaultVoice = "palette-default", dialogueVoices = listOf("voice-a", "voice-b"))
        val router = TtsProviderRouter(
            listOf(provider),
            maxRetries = 0,
            maxConcurrency = 1,
            sleeper = {},
            voicePalette = palette,
        )

        assertEquals("explicit", router.candidates(TtsRequest(text = "Hi", voice = "explicit"), 1.0)[0].request.voice)
        assertEquals("palette-default", router.candidates(TtsRequest(text = "Hi"), 1.0)[0].request.voice)
        assertEquals("voice-a", router.candidates(TtsRequest(text = "Hi").asSpeaker(0), 1.0)[0].request.voice)
        assertEquals("voice-b", router.candidates(TtsRequest(text = "Hi").asSpeaker(1), 1.0)[0].request.voice)
        assertEquals(
            "explicit",
            router.candidates(TtsRequest(text = "Hi", voice = "explicit").asSpeaker(0), 1.0)[0].request.voice,
        )
    }
}
