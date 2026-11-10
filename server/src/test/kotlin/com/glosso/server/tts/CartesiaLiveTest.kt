package com.glosso.server.tts

import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Optional check against the real Cartesia API. It does NOT run by default: it
 * returns early unless both `CARTESIA_API_KEY` and `CARTESIA_DEFAULT_VOICE` are
 * present in the environment. The key is never read from the repo or logged.
 */
class CartesiaLiveTest {

    @Test
    fun `synthesizes a phrase against the real API when credentials are provided`() {
        val key = System.getenv("CARTESIA_API_KEY")
        val voice = System.getenv("CARTESIA_DEFAULT_VOICE")
        if (key.isNullOrBlank() || voice.isNullOrBlank()) {
            println(
                "CartesiaLiveTest skipped: export CARTESIA_API_KEY and CARTESIA_DEFAULT_VOICE to run it",
            )
            return
        }

        val providerConfig = ProviderConfig(
            id = CartesiaTtsProvider.ID,
            apiKey = key,
            model = CartesiaTtsProvider.DEFAULT_MODEL,
            defaultVoice = voice,
            baseUrl = CartesiaTtsProvider.DEFAULT_BASE_URL,
        )
        val transport = JdkHttpTransport(Duration.ofSeconds(60))
        try {
            val provider = CartesiaTtsProvider(
                providerConfig,
                transport,
                TtsPricing(mapOf("cartesia" to BigDecimal("40.0"))),
            )
            val result = provider.synthesize(
                TtsRequest(text = "Hello, this is a Glosso pronunciation test.", language = "en", speed = TtsSpeed.SLOW),
                0.7,
            )
            assertEquals("cartesia", result.provider)
            assertTrue(result.audio.size > 1_000, "expected real audio bytes")
        } finally {
            transport.close()
        }
    }
}
