package com.glosso.server.generation

import com.glosso.server.tts.AudioValidator
import com.glosso.server.tts.InMemoryObjectStore
import com.glosso.server.tts.InMemoryTtsAssetStore
import com.glosso.server.tts.TtsConfig
import com.glosso.server.tts.TtsJobProcessor
import com.glosso.server.tts.TtsPricing
import com.glosso.server.tts.TtsProvider
import com.glosso.server.tts.TtsProviderRouter
import com.glosso.server.tts.TtsService
import com.glosso.server.tts.testConfig

/** A real TtsService over fake providers, so the worker is tested end to end. */
internal class TtsHarness(
    val processor: TtsJobProcessor,
    val objectStore: InMemoryObjectStore,
    val assetStore: InMemoryTtsAssetStore,
    val config: TtsConfig,
)

internal fun ttsHarness(vararg providers: TtsProvider, config: TtsConfig = testConfig()): TtsHarness {
    val objectStore = InMemoryObjectStore()
    val assetStore = InMemoryTtsAssetStore()
    val router = TtsProviderRouter(
        providers = providers.toList(),
        maxRetries = config.maxRetries,
        maxConcurrency = config.maxConcurrency,
        sleeper = {},
    )
    val service = TtsService(
        router = router,
        objectStore = objectStore,
        assetStore = assetStore,
        validator = AudioValidator(),
        pricing = TtsPricing(config.pricing),
        config = config,
    )
    return TtsHarness(TtsJobProcessor(service), objectStore, assetStore, config)
}
