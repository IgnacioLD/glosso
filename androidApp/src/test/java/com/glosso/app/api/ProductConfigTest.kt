package com.glosso.app.api

import com.glosso.app.product.VersionNotice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /v1/config` and `GET /v1/me` decoded into the client model. The client
 * reads only the flags, the version policy and the linked accounts; the keys a
 * server still sends for older app versions (the beta window, plans, prices,
 * founder fields) must decode without breaking anything.
 */
class ProductConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val current = """
        {
          "schema": 1,
          "mode": "BETA",
          "beta": { "active": true },
          "flags": { "accounts": true },
          "client": { "minimumSupportedVersion": 2210, "recommendedVersion": 2219, "forceUpdate": false }
        }
    """.trimIndent()

    private fun config() = json.decodeFromString<ProductConfig>(current)

    @Test
    fun `the config decodes the flags and the version policy`() {
        val config = config()
        assertEquals(1, config.schema)
        assertTrue(config.flag("accounts"))
        assertFalse("a missing flag is false", config.flag("does_not_exist"))
        assertEquals(2210, config.client.minimumSupportedVersion)
        assertEquals(2219, config.client.recommendedVersion)
    }

    @Test
    fun `the version notice warns without ever blocking`() {
        val config = config()
        assertFalse(VersionNotice.from(config, currentVersion = 2219).visible)

        val outdated = VersionNotice.from(config, currentVersion = 2210)
        assertTrue(outdated.outdated)
        assertFalse("2210 is exactly the minimum, not below it", outdated.belowMinimum)

        val below = VersionNotice.from(config, currentVersion = 2000)
        assertTrue(below.belowMinimum)
        assertTrue(below.visible)
    }

    @Test
    fun `an empty config shows no version notice`() {
        assertFalse(VersionNotice.from(ProductConfig(), currentVersion = 1).visible)
    }

    @Test
    fun `a legacy config body with plans and null pricing still decodes`() {
        val config = json.decodeFromString<ProductConfig>(fixture("legacy-product-config.json"))
        assertTrue(config.flag("beta_banner"))
        assertFalse(config.flag("pro"))
        assertEquals(2210, config.client.minimumSupportedVersion)
    }

    @Test
    fun `a legacy me body with plan and founder fields still decodes`() {
        val account = json.decodeFromString<AccountState>(fixture("legacy-me.json"))
        assertNull(account.linkedAccounts)
        assertEquals(listOf("google"), json.decodeFromString<AccountState>("""{"linkedAccounts":["google"]}""").linkedAccounts)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }
}
