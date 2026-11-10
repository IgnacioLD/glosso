package com.glosso.server.tts

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Estimates the cost of a generation from the character count. Rates are
 * configuration (`TTS_COST_PER_1M_CHARS_<PROVIDER>`), never invented in code: an
 * unknown rate estimates 0 and still records the characters, so the cost of a
 * batch can be answered later once the real plan rates are set.
 */
class TtsPricing(private val usdPerMillionChars: Map<String, BigDecimal>) {

    fun estimate(provider: String, model: String, characters: Int): BigDecimal {
        if (characters <= 0) return BigDecimal.ZERO
        val rate = usdPerMillionChars["${provider.lowercase()}:${model.lowercase()}"]
            ?: usdPerMillionChars[provider.lowercase()]
            ?: return BigDecimal.ZERO
        return rate.multiply(BigDecimal(characters))
            .divide(ONE_MILLION, 6, RoundingMode.HALF_UP)
    }

    private companion object {
        val ONE_MILLION: BigDecimal = BigDecimal(1_000_000)
    }
}
