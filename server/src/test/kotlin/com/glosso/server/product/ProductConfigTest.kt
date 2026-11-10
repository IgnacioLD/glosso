package com.glosso.server.product

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The environment is the single authority for the config names and their safe
 * defaults. These lock the defaults, the overrides and the exact served shape.
 */
class ProductConfigTest {

    @Test
    fun `defaults declare no flags and the default version policy`() {
        val config = ProductConfig.fromEnv(emptyMap())

        assertEquals(2210, config.client.minimumSupportedVersion)
        assertEquals(2219, config.client.recommendedVersion)
        assertEquals(false, config.client.forceUpdate)
        assertTrue(config.flags.isEmpty())
    }

    @Test
    fun `the environment overrides every name`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_MIN_CLIENT_VERSION" to "2300",
                "GLOSSO_RECOMMENDED_CLIENT_VERSION" to "2400",
                "GLOSSO_FORCE_UPDATE" to "true",
                "GLOSSO_FLAG_SOMETHING_NEW" to "true",
                "GLOSSO_FLAG_OTHER" to "off",
            ),
        )

        assertEquals(2300, config.client.minimumSupportedVersion)
        assertEquals(2400, config.client.recommendedVersion)
        assertEquals(true, config.client.forceUpdate)
        assertEquals(mapOf("other" to false, "something_new" to true), config.flags)
    }

    @Test
    fun `blank variables from docker compose count as unset`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_MIN_CLIENT_VERSION" to "",
                "GLOSSO_FORCE_UPDATE" to " ",
                "GLOSSO_FLAG_ACCOUNTS" to "",
            ),
        )
        assertEquals(ProductConfig.DEFAULT_MIN_VERSION, config.client.minimumSupportedVersion)
        assertEquals(false, config.client.forceUpdate)
        assertTrue(config.flags.isEmpty())
    }

    @Test
    fun `the recommended version never sits below the minimum`() {
        val config = ProductConfig.fromEnv(
            mapOf("GLOSSO_MIN_CLIENT_VERSION" to "2300", "GLOSSO_RECOMMENDED_CLIENT_VERSION" to "2250"),
        )
        assertEquals(2300, config.client.recommendedVersion)
    }

    @Test
    fun `the served JSON is the contract shape`() {
        val json = Json.parseToJsonElement(
            ProductConfig.fromEnv(mapOf("GLOSSO_FLAG_ACCOUNTS" to "true")).toJson(),
        ).jsonObject

        assertEquals(setOf("schema", "mode", "beta", "flags", "client"), json.keys)
        assertEquals(1, json.getValue("schema").jsonPrimitive.int)
        // Older app builds unlock every lesson only while they read an open beta.
        assertEquals("BETA", json.getValue("mode").jsonPrimitive.content)
        assertEquals(true, json.getValue("beta").jsonObject.getValue("active").jsonPrimitive.boolean)
        assertEquals(true, json.getValue("flags").jsonObject.getValue("accounts").jsonPrimitive.boolean)

        val client = json.getValue("client").jsonObject
        assertEquals(2210, client.getValue("minimumSupportedVersion").jsonPrimitive.int)
        assertEquals(2219, client.getValue("recommendedVersion").jsonPrimitive.int)
        assertEquals(false, client.getValue("forceUpdate").jsonPrimitive.boolean)
    }
}
