package com.glosso.server.product

import com.glosso.server.api.ClientConfigDto
import com.glosso.server.api.ProductConfigResponse
import kotlinx.serialization.json.Json

data class ClientConfig(
    val minimumSupportedVersion: Int,
    val recommendedVersion: Int,
    val forceUpdate: Boolean,
)

/**
 * The product configuration, read once from the environment in [fromEnv] and
 * served by `GET /v1/config`: feature flags and the client version policy.
 * Glosso is free for everyone, so there are no plans, prices or phases.
 * Environment names live in docs/PRODUCT-CONFIG-CONTRACT.md.
 */
data class ProductConfig(
    val flags: Map<String, Boolean>,
    val client: ClientConfig,
) {
    fun toResponse(): ProductConfigResponse = ProductConfigResponse(
        flags = flags,
        client = ClientConfigDto(
            minimumSupportedVersion = client.minimumSupportedVersion,
            recommendedVersion = client.recommendedVersion,
            forceUpdate = client.forceUpdate,
        ),
    )

    fun toJson(): String = CONFIG_JSON.encodeToString(ProductConfigResponse.serializer(), toResponse())

    companion object {
        const val DEFAULT_MIN_VERSION = 2210
        const val DEFAULT_RECOMMENDED_VERSION = 2219

        private val CONFIG_JSON = Json { encodeDefaults = true }

        private const val FLAG_PREFIX = "GLOSSO_FLAG_"

        fun fromEnv(env: Map<String, String> = System.getenv()): ProductConfig {
            val minVersion = env.value("GLOSSO_MIN_CLIENT_VERSION")?.toIntOrNull() ?: DEFAULT_MIN_VERSION
            return ProductConfig(
                flags = parseFlags(env),
                client = ClientConfig(
                    minimumSupportedVersion = minVersion,
                    // A recommendation below the floor would be meaningless.
                    recommendedVersion = maxOf(
                        minVersion,
                        env.value("GLOSSO_RECOMMENDED_CLIENT_VERSION")?.toIntOrNull() ?: DEFAULT_RECOMMENDED_VERSION,
                    ),
                    forceUpdate = parseBoolean(env.value("GLOSSO_FORCE_UPDATE"), default = false),
                ),
            )
        }

        /** A blank variable is the same as a missing one: docker compose injects `""` for unset values. */
        private fun Map<String, String>.value(name: String): String? = this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun parseFlags(env: Map<String, String>): Map<String, Boolean> =
            env.entries
                .filter { it.key.startsWith(FLAG_PREFIX) && it.key.length > FLAG_PREFIX.length && it.value.isNotBlank() }
                .associate { it.key.removePrefix(FLAG_PREFIX).lowercase() to parseBoolean(it.value, default = false) }
                .toSortedMap()

        private fun parseBoolean(raw: String?, default: Boolean): Boolean = when (raw?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }
}
