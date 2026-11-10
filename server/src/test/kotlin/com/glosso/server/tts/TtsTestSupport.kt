package com.glosso.server.tts

import java.math.BigDecimal

/** Shared test doubles. No network, no secrets. */

/** Queue-based or handler-based HTTP transport for provider tests. */
class FakeHttpTransport(
    private val handler: (HttpTransport.Request) -> HttpTransport.Response,
) : HttpTransport {
    val requests = mutableListOf<HttpTransport.Request>()

    override fun send(request: HttpTransport.Request): HttpTransport.Response {
        requests.add(request)
        return handler(request)
    }

    companion object {
        fun responding(vararg responses: HttpTransport.Response): FakeHttpTransport {
            val queue = ArrayDeque(responses.toList())
            return FakeHttpTransport {
                queue.removeFirstOrNull() ?: error("FakeHttpTransport ran out of queued responses")
            }
        }
    }
}

/** Valid-enough MP3 bytes: MPEG frame sync plus padding, above the min size. */
fun validAudioBytes(size: Int = 512): ByteArray = ByteArray(size) { index ->
    when (index) {
        0 -> 0xFF.toByte()
        1 -> 0xFB.toByte()
        else -> 0x20
    }
}

fun fakeResult(
    provider: String = "fake",
    model: String = "fake-model",
    voice: String = "fake-voice",
    audio: ByteArray = validAudioBytes(),
    format: AudioFormat = AudioFormat(),
    speed: TtsSpeed = TtsSpeed.NORMAL,
    speedFactor: Double = 1.0,
    durationMs: Long? = 1_000,
    characters: Int = 10,
    credits: Double? = null,
    cost: BigDecimal = BigDecimal.ZERO,
    metadata: Map<String, String> = emptyMap(),
): TtsResult = TtsResult(
    audio = audio,
    format = format,
    provider = provider,
    model = model,
    voice = voice,
    speed = speed,
    speedFactor = speedFactor,
    durationMs = durationMs,
    characters = characters,
    credits = credits,
    estimatedCostUsd = cost,
    metadata = metadata,
)

class FakeTtsProvider(
    override val id: String,
    private val model: String = "fake-model",
    private val voice: String = "fake-voice",
    override val supportedLanguages: Set<String> = emptySet(),
    private val behavior: (TtsRequest, Double) -> TtsResult,
) : TtsProvider {

    var calls: Int = 0
        private set

    override fun defaultModel(): String = model

    override fun defaultVoice(): String = voice

    override fun synthesize(request: TtsRequest, speedFactor: Double): TtsResult {
        calls++
        return behavior(request, speedFactor)
    }
}

/** Fails [failures] times with [kind], then succeeds. */
class FlakyTtsProvider(
    override val id: String,
    private val kind: TtsFailureKind,
    private val failures: Int,
) : TtsProvider {
    var calls: Int = 0
        private set

    override fun defaultModel(): String = "flaky-model"
    override fun defaultVoice(): String = "flaky-voice"

    override fun synthesize(request: TtsRequest, speedFactor: Double): TtsResult {
        calls++
        if (calls <= failures) throw TtsException(kind, "boom #$calls")
        return fakeResult(provider = id, speed = request.speed, speedFactor = speedFactor)
    }
}

/** A well-behaved fake that echoes the requested speed, so normal != slow. */
fun echoingFakeProvider(id: String = "fake", model: String = "fake-model"): FakeTtsProvider =
    FakeTtsProvider(id, model = model) { request, factor ->
        fakeResult(provider = id, model = model, speed = request.speed, speedFactor = factor)
    }

class RecordingTtsObservability : TtsObservability {
    val events = mutableListOf<TtsEvent>()

    override fun record(event: TtsEvent) {
        events.add(event)
    }

    fun phases(): List<String> = events.map { it.phase }
}

fun testConfig(
    provider: String = "cartesia",
    fallbacks: List<String> = emptyList(),
    maxRetries: Int = 2,
    slowSpeedFactor: Double = 0.7,
    pricing: Map<String, BigDecimal> = mapOf("cartesia" to BigDecimal("40.0")),
    outputFormat: AudioFormat = AudioFormat(),
): TtsConfig = TtsConfig(
    provider = provider,
    fallbackProviders = fallbacks,
    maxConcurrency = 2,
    maxRetries = maxRetries,
    timeoutSeconds = 30,
    slowSpeedFactor = slowSpeedFactor,
    outputFormat = outputFormat,
    objectKeyPrefix = "tts",
    cacheDir = null,
    r2 = null,
    cartesia = ProviderConfig(
        id = "cartesia",
        apiKey = "test-cartesia-key",
        model = "sonic-3.6",
        defaultVoice = "voice-cartesia",
        baseUrl = "https://api.cartesia.test",
    ),
    elevenlabs = ProviderConfig(
        id = "elevenlabs",
        apiKey = "test-elevenlabs-key",
        model = "eleven_multilingual_v2",
        defaultVoice = "voice-eleven",
        baseUrl = "https://api.elevenlabs.test",
    ),
    pricing = pricing,
)
