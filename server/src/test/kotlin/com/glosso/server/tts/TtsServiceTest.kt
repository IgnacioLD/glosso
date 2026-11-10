package com.glosso.server.tts

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TtsServiceTest {

    private data class Harness(
        val service: TtsService,
        val objectStore: InMemoryObjectStore,
        val assetStore: InMemoryTtsAssetStore,
        val config: TtsConfig,
        val observability: RecordingTtsObservability,
    )

    private fun harness(
        providers: List<TtsProvider>,
        config: TtsConfig = testConfig(),
        observability: RecordingTtsObservability = RecordingTtsObservability(),
    ): Harness {
        val objectStore = InMemoryObjectStore()
        val assetStore = InMemoryTtsAssetStore()
        val router = TtsProviderRouter(
            providers = providers,
            maxRetries = config.maxRetries,
            maxConcurrency = config.maxConcurrency,
            observability = observability,
            sleeper = {},
        )
        val service = TtsService(
            router = router,
            objectStore = objectStore,
            assetStore = assetStore,
            validator = AudioValidator(),
            pricing = TtsPricing(config.pricing),
            config = config,
            observability = observability,
        )
        return Harness(service, objectStore, assetStore, config, observability)
    }

    private fun cartesiaProvider(
        behavior: (TtsRequest, Double) -> TtsResult = { request, factor ->
            fakeResult(
                provider = "cartesia",
                model = "sonic-3.6",
                voice = "voice-cartesia",
                speed = request.speed,
                speedFactor = factor,
                characters = 5,
                cost = BigDecimal("0.000200"),
            )
        },
    ) = FakeTtsProvider("cartesia", model = "sonic-3.6", voice = "voice-cartesia", behavior = behavior)

    private fun job(id: String = "job-1", text: String = "Hello", speed: TtsSpeed = TtsSpeed.NORMAL) =
        TtsJob(id, TtsRequest(text = text, language = "en", speed = speed), contentId = "content-1")

    @Test
    fun `second run is a cache hit and does not pay again`() {
        val primary = cartesiaProvider()
        val h = harness(listOf(primary))

        val first = h.service.synthesize(job())
        assertEquals(TtsJobStatus.PUBLISHED, first.status)
        assertFalse(first.cacheHit)
        assertEquals(1, primary.calls)
        assertNotNull(first.asset)
        assertEquals(BigDecimal("0.000200"), h.assetStore.totalEstimatedCostUsd())
        assertTrue(h.objectStore.exists(first.asset!!.objectKey))

        val second = h.service.synthesize(job())
        assertEquals(TtsJobStatus.ALREADY_PUBLISHED, second.status)
        assertTrue(second.cacheHit)
        assertEquals(1, primary.calls) // provider not called again
        assertEquals(BigDecimal("0.000200"), h.assetStore.totalEstimatedCostUsd())
        assertEquals(1, h.assetStore.count())
    }

    @Test
    fun `normal and slow are separate assets with different keys`() {
        val primary = cartesiaProvider()
        val h = harness(listOf(primary))

        val normal = h.service.synthesize(job(speed = TtsSpeed.NORMAL))
        val slow = h.service.synthesize(job(speed = TtsSpeed.SLOW))

        assertEquals(TtsJobStatus.PUBLISHED, normal.status)
        assertEquals(TtsJobStatus.PUBLISHED, slow.status)
        assertEquals(2, primary.calls)
        assertEquals(2, h.assetStore.count())
        assertTrue(normal.asset!!.cacheKey != slow.asset!!.cacheKey)
        assertEquals("normal", normal.asset!!.speed)
        assertEquals("slow", slow.asset!!.speed)
        assertEquals(0.7, slow.asset!!.speedFactor)
    }

    @Test
    fun `synthesizePair emits both variants`() {
        val primary = cartesiaProvider()
        val h = harness(listOf(primary))
        val results = h.service.synthesizePair(job())
        assertEquals(2, results.size)
        assertEquals(setOf("normal", "slow"), results.mapNotNull { it.asset?.speed }.toSet())
        assertEquals(2, primary.calls)
    }

    @Test
    fun `a provider option produces a distinct asset`() {
        val primary = cartesiaProvider()
        val h = harness(listOf(primary))
        val plain = job()
        val emotional = plain.copy(
            request = plain.request.copy(providerOptions = mapOf("emotion" to "excited")),
        )

        val first = h.service.synthesize(plain)
        val second = h.service.synthesize(emotional)

        assertEquals(TtsJobStatus.PUBLISHED, first.status)
        assertEquals(TtsJobStatus.PUBLISHED, second.status)
        assertNotEquals(first.asset!!.cacheKey, second.asset!!.cacheKey)
        assertEquals(2, primary.calls)
        assertEquals(2, h.assetStore.count())
    }

    @Test
    fun `invalid audio fails the job and publishes nothing`() {
        val primary = cartesiaProvider { _, _ ->
            fakeResult(provider = "cartesia", model = "sonic-3.6", voice = "voice-cartesia", audio = ByteArray(0))
        }
        val h = harness(listOf(primary))

        val result = h.service.synthesize(job())
        assertEquals(TtsJobStatus.FAILED, result.status)
        assertEquals(TtsFailureKind.AUDIO_INVALID, result.errorKind)
        assertEquals(0, h.assetStore.count())
        assertEquals(BigDecimal.ZERO, h.assetStore.totalEstimatedCostUsd())
        assertTrue(h.observability.phases().contains("failed"))
    }

    @Test
    fun `permanent provider error fails without fallback`() {
        val primary = FakeTtsProvider("cartesia") { _, _ ->
            throw TtsException(TtsFailureKind.INVALID_TEXT, "bad text")
        }
        val fallback = cartesiaProvider()
        val h = harness(listOf(primary, fallback))

        val result = h.service.synthesize(job())
        assertEquals(TtsJobStatus.FAILED, result.status)
        assertEquals(TtsFailureKind.INVALID_TEXT, result.errorKind)
        assertEquals(0, fallback.calls)
        assertTrue(primary.calls >= 1)
    }

    @Test
    fun `transient failure falls back and the asset is cached under the fallback`() {
        val primary = FakeTtsProvider("cartesia") { _, _ ->
            throw TtsException(TtsFailureKind.SERVER_ERROR, "down")
        }
        val fallback = FakeTtsProvider(
            "elevenlabs",
            model = "eleven_multilingual_v2",
            voice = "voice-eleven",
        ) { _, _ ->
            fakeResult(provider = "elevenlabs", model = "eleven_multilingual_v2", voice = "voice-eleven", characters = 5)
        }
        val h = harness(listOf(primary, fallback))

        val first = h.service.synthesize(job())
        assertEquals(TtsJobStatus.PUBLISHED, first.status)
        assertEquals("elevenlabs", first.provider)
        assertTrue(first.fallbackUsed)

        val callsAfterFirst = fallback.calls
        val second = h.service.synthesize(job())
        assertEquals(TtsJobStatus.ALREADY_PUBLISHED, second.status)
        assertTrue(second.cacheHit)
        assertEquals(callsAfterFirst, fallback.calls) // no new call: cache lookup wins
    }

    @Test
    fun `re-running a job after upload but before marking recovers the asset`() {
        val primary = cartesiaProvider()
        val h = harness(listOf(primary))

        val cacheKey = TtsFingerprint.key(
            text = "Hello",
            language = "en",
            provider = "cartesia",
            model = "sonic-3.6",
            voice = "voice-cartesia",
            speedFactor = 1.0,
            format = AudioFormat(),
        )
        val objectKey = TtsFingerprint.objectKey("tts", cacheKey, AudioFormat())
        h.objectStore.put(objectKey, validAudioBytes(), "audio/mpeg")

        val result = h.service.synthesize(job())
        assertEquals(TtsJobStatus.ALREADY_PUBLISHED, result.status)
        assertTrue(result.cacheHit)
        assertEquals(0, primary.calls) // recovered from storage, no regeneration
        assertEquals(1, h.assetStore.count())
    }

    @Test
    fun `a failed job records which provider failed`() {
        val primary = FakeTtsProvider("cartesia") { _, _ ->
            throw TtsException(TtsFailureKind.INVALID_TEXT, "bad text")
        }
        val fallback = cartesiaProvider()
        val h = harness(listOf(primary, fallback))

        val result = h.service.synthesize(job())
        assertEquals(TtsJobStatus.FAILED, result.status)
        assertEquals("cartesia", result.provider)
        assertTrue(result.errorMessage!!.contains("cartesia"))
        assertEquals("cartesia", h.observability.events.last().provider)
    }

    @Test
    fun `failed jobs record no cost`() {
        val primary = FakeTtsProvider("cartesia") { _, _ ->
            throw TtsException(TtsFailureKind.RATE_LIMITED, "429")
        }
        val h = harness(listOf(primary))
        val result = h.service.synthesize(job())
        assertEquals(TtsJobStatus.FAILED, result.status)
        assertEquals(BigDecimal.ZERO, h.assetStore.totalEstimatedCostUsd())
    }
}
