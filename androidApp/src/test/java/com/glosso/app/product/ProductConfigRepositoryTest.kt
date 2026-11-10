package com.glosso.app.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository's decode path. [ProductConfigRepository.refresh] keeps a
 * fetched body only when it decodes; a decoding failure is swallowed and the
 * state silently stays where it was, so a body the server really serves must
 * never be dropped.
 */
class ProductConfigRepositoryTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `a legacy config body is kept, not discarded`() {
        val config = ProductConfigRepository.decodeConfig(fixture("legacy-product-config.json"))

        assertNotNull("a body with null limits/pricing must not be dropped", config)
        assertTrue(config!!.flag("beta_banner"))
        assertEquals(2219, config.client.recommendedVersion)
    }

    @Test
    fun `a legacy me body is kept, not discarded`() {
        assertNotNull(ProductConfigRepository.decodeAccount(fixture("legacy-me.json")))
    }

    @Test
    fun `a garbage body is still discarded`() {
        assertNull(ProductConfigRepository.decodeConfig("not json"))
        assertNull(ProductConfigRepository.decodeAccount("not json"))
    }

    @Test
    fun `a config round-trips through the cache encoding`() {
        val config = ProductConfigRepository.decodeConfig(fixture("legacy-product-config.json"))!!
        val reloaded = ProductConfigRepository.decodeConfig(ProductConfigRepository.encodeConfig(config))
        assertEquals(config, reloaded)
    }
}
