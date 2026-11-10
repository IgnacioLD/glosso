package com.glosso.server.tts

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class TtsPricingTest {

    private val pricing = TtsPricing(
        mapOf(
            "cartesia" to BigDecimal("40.0"),
            "elevenlabs:eleven_multilingual_v2" to BigDecimal("150.0"),
        ),
    )

    @Test
    fun `cost scales with characters`() {
        // 500_000 chars at 40 USD per million = 20 USD.
        assertEquals(BigDecimal("20.000000"), pricing.estimate("cartesia", "sonic-3.6", 500_000))
    }

    @Test
    fun `model specific rate wins over provider rate`() {
        assertEquals(
            BigDecimal("15.000000"),
            pricing.estimate("elevenlabs", "eleven_multilingual_v2", 100_000),
        )
    }

    @Test
    fun `unknown rate estimates zero without failing`() {
        assertEquals(BigDecimal.ZERO, pricing.estimate("openai", "gpt-4o-tts", 1_000))
    }

    @Test
    fun `no characters costs nothing`() {
        assertEquals(BigDecimal.ZERO, pricing.estimate("cartesia", "sonic-3.6", 0))
    }
}
