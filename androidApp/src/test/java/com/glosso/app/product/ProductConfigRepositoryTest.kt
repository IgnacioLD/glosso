package com.glosso.app.product

import com.glosso.app.api.ProductConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository's decode path against the bodies the server really serves.
 *
 * [ProductConfigRepository.refresh] keeps a fetched body only when it decodes;
 * a `JsonDecodingException` is swallowed and the state silently stays on
 * [ProductConfig.BetaFallback]. The mock used by the E2E answered a simplified
 * shape, so the real bodies were never decoded until production. These tests
 * feed the real `GET /v1/config` (with `"limits": null` and `"pricing": null`)
 * and `GET /v1/me` (with `betaFounderBenefit` as a string) to the exact decode
 * the repository uses.
 */
class ProductConfigRepositoryTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `the real config body is kept, not discarded`() {
        val config = ProductConfigRepository.decodeConfig(fixture("server-product-config.json"))

        assertNotNull("a real body must not be dropped for a null limits/pricing", config)
        assertTrue(config!!.isBeta)
        assertEquals("2026-12-31T23:59:59Z", config.beta.endDate)
        assertNull(config.limits)
        assertNull(config.pricing)
        assertEquals(setOf("FREE", "PRO", "PERSONALIZED"), config.plans.keys)
        assertTrue(config.flag("beta_banner"))
    }

    @Test
    fun `the real me body is kept, not discarded`() {
        val account = ProductConfigRepository.decodeAccount(fixture("server-me.json"))

        assertNotNull(account)
        assertEquals("FREE", account!!.plan)
        assertTrue(account.betaFounder)
        assertEquals("Ventaja Beta Founder", account.betaFounderBenefit)
    }

    @Test
    fun `a garbage body is still discarded so the fallback stays`() {
        assertNull(ProductConfigRepository.decodeConfig("not json"))
        assertNull(ProductConfigRepository.decodeAccount("not json"))
    }

    @Test
    fun `the real bodies round-trip through the cache encoding`() {
        val config = ProductConfigRepository.decodeConfig(fixture("server-product-config.json"))!!
        val encoded = ProductConfigRepository.encodeConfig(config)
        val reloaded = ProductConfigRepository.decodeConfig(encoded)
        assertNotNull(reloaded)
        assertTrue(reloaded!!.isBeta)
        assertNull(reloaded.limits)
        assertNull(reloaded.pricing)
    }

    @Test
    fun `a config missing mode still assumes the open beta, never a silent lock`() {
        val partial = """{"beta":{"active":true,"endDate":"2026-12-31T23:59:59Z"},"flags":{}}"""
        val config = ProductConfigRepository.decodeConfig(partial)

        assertNotNull(config)
        assertTrue("the server default is BETA, and the safe client default is too", config!!.isBeta)
        assertFalse(config.flag("pro"))
    }
}
