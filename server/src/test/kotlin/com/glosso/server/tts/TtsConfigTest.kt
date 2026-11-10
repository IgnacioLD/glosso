package com.glosso.server.tts

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsConfigTest {

    @Test
    fun `reads provider, voices and pricing from the environment`() {
        val config = TtsConfig.fromEnv(
            mapOf(
                "TTS_PROVIDER" to "elevenlabs",
                "TTS_FALLBACK_PROVIDERS" to "cartesia",
                "TTS_MAX_CONCURRENCY" to "4",
                "TTS_SLOW_SPEED" to "0.6",
                "CARTESIA_DEFAULT_VOICE" to "cartesia-voice",
                "TTS_VOICE_DEFAULT" to "palette-default",
                "TTS_DIALOGUE_VOICES" to "voice-a, voice-b ,voice-c",
                "TTS_COST_PER_1M_CHARS_CARTESIA" to "40",
            ),
        )
        assertEquals("elevenlabs", config.provider)
        assertEquals(listOf("cartesia"), config.fallbackProviders)
        assertEquals(4, config.maxConcurrency)
        assertEquals(0.6, config.slowSpeedFactor)
        assertEquals("cartesia-voice", config.cartesia.defaultVoice)
        assertEquals("palette-default", config.voicePalette.defaultVoice)
        assertEquals(listOf("voice-a", "voice-b", "voice-c"), config.voicePalette.dialogueVoices)
        assertEquals(BigDecimal("40"), config.pricing["cartesia"])
    }

    @Test
    fun `defaults are safe and provider agnostic`() {
        val config = TtsConfig.fromEnv(emptyMap())
        assertEquals("cartesia", config.provider)
        assertTrue(config.fallbackProviders.isEmpty())
        assertEquals("sonic-3.6", config.cartesia.model)
        assertEquals(0.7, config.slowSpeedFactor)
        assertTrue(config.voicePalette.dialogueVoices.isEmpty())
        assertTrue(config.pricing.isEmpty())
    }

    @Test
    fun `cartesia token settings come from the environment`() {
        val defaults = TtsConfig.fromEnv(mapOf("CARTESIA_VERSION" to "2026-08-14"))
        assertEquals("2026-08-14", defaults.cartesia.extra["tokenVersion"])
        assertEquals("tts", defaults.cartesia.extra["tokenGrants"])
        assertEquals("120", defaults.cartesia.extra["tokenExpiresIn"])
        assertEquals("30", defaults.cartesia.extra["tokenRefreshSkewSeconds"])

        val configured = TtsConfig.fromEnv(
            mapOf(
                "CARTESIA_TOKEN_VERSION" to "2026-09-01",
                "CARTESIA_TOKEN_GRANTS" to "tts",
                "CARTESIA_TOKEN_EXPIRES_IN" to "600",
                "CARTESIA_TOKEN_REFRESH_SKEW_SECONDS" to "120",
            ),
        )
        assertEquals("2026-09-01", configured.cartesia.extra["tokenVersion"])
        assertEquals("tts", configured.cartesia.extra["tokenGrants"])
        assertEquals("600", configured.cartesia.extra["tokenExpiresIn"])
        assertEquals("120", configured.cartesia.extra["tokenRefreshSkewSeconds"])
    }

    @Test
    fun `r2 is only built when every credential is present`() {
        val partial = TtsConfig.fromEnv(mapOf("GLOSSO_R2_ENDPOINT" to "https://r2.test"))
        assertEquals(null, partial.r2)

        val full = TtsConfig.fromEnv(
            mapOf(
                "GLOSSO_R2_ENDPOINT" to "https://r2.test",
                "GLOSSO_R2_BUCKET" to "glosso",
                "GLOSSO_R2_ACCESS_KEY" to "ak",
                "GLOSSO_R2_SECRET_KEY" to "sk",
            ),
        )
        assertEquals("glosso", full.r2?.bucket)
    }
}
