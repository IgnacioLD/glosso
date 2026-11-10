package com.glosso.server.product

import com.glosso.server.api.BetaDto
import com.glosso.server.api.ClientConfigDto
import com.glosso.server.api.FounderBenefitDto
import com.glosso.server.api.ProductConfigResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger("GlossoProductConfig")

/** The active phase of the product. `PRO`/`PERSONALIZED` are plans, not phases. */
enum class ProductMode { BETA, FREE }

/**
 * The Beta Founder benefit. [kind] `PRO_LIFETIME` (the default) grants PRO for
 * life as a one-time lifetime grant, never PERSONALIZED; any other kind grants
 * no plan. [until] is kept for the contract shape and is null: the benefit never
 * expires.
 */
data class FounderBenefitConfig(
    val kind: String?,
    val value: Int?,
    val until: Instant?,
    val label: String?,
) {
    val grantsProLifetime: Boolean get() = kind.equals(ProductConfig.PRO_LIFETIME, ignoreCase = true)
}

/**
 * Two independent keys that happen to share their default. [endDate] closes the
 * free beta; [founderUntil] closes the Beta Founder window (every alta on or
 * before it is a founder). Neither controls the other.
 */
data class BetaConfig(
    val endDate: Instant,
    val founderEnabled: Boolean,
    val founderBenefit: FounderBenefitConfig,
    val founderUntil: Instant = ProductConfig.DEFAULT_FOUNDER_UNTIL,
)

data class ClientConfig(
    val minimumSupportedVersion: Int,
    val recommendedVersion: Int,
    val forceUpdate: Boolean,
)

/**
 * The single source of product configuration, read once from the environment in
 * [fromEnv] and served by `GET /v1/config`. Defaults are safe: the beta is on,
 * the founder benefit is not priced, and nobody is force-updated.
 *
 * Frozen environment names and defaults live in
 * docs/PRODUCT-CONFIG-CONTRACT.md section 6. `limits` and `pricing` default to
 * null ("not decided yet") so no figure is ever invented in code.
 *
 * [mode] is the configured phase; the served phase also honours the calendar:
 * past [BetaConfig.endDate] the beta is over even if nobody edited the mode.
 */
data class ProductConfig(
    val mode: ProductMode,
    val beta: BetaConfig,
    /** Explicit flags. `beta_banner`, when absent, follows whether the beta is active. */
    val flags: Map<String, Boolean>,
    val client: ClientConfig,
    val limits: JsonElement? = null,
    val pricing: JsonElement? = null,
    val plans: Map<String, JsonObject> = DEFAULT_PLANS,
) {
    /** True while the free beta is open: configured as BETA and not past the end date. */
    fun betaActiveAt(now: Instant): Boolean = mode == ProductMode.BETA && !now.isAfter(beta.endDate)

    fun modeAt(now: Instant): ProductMode = if (betaActiveAt(now)) ProductMode.BETA else ProductMode.FREE

    /** An alta on or before [BetaConfig.founderUntil] is a founder; the beta end date plays no part. */
    fun isFounderAlta(createdAt: Instant): Boolean = beta.founderEnabled && !createdAt.isAfter(beta.founderUntil)

    fun flagsAt(now: Instant): Map<String, Boolean> {
        val served = linkedMapOf("beta_banner" to (flags["beta_banner"] ?: betaActiveAt(now)))
        served.putAll(flags)
        return served
    }

    /**
     * What a plan includes. While the beta is open everybody gets the full
     * access of the top plan, free and whatever their own plan is; the flags
     * still decide which of those features exist yet.
     */
    fun entitlementsFor(plan: String, now: Instant): JsonObject =
        if (betaActiveAt(now)) plans.getValue(TOP_PLAN) else plans[plan] ?: plans.getValue("FREE")

    fun toResponse(now: Instant = Instant.now()): ProductConfigResponse = ProductConfigResponse(
        mode = modeAt(now).name,
        beta = BetaDto(
            active = betaActiveAt(now),
            endDate = ISO.format(beta.endDate),
            founderEnabled = beta.founderEnabled,
            founderBenefit = FounderBenefitDto(
                kind = beta.founderBenefit.kind,
                value = beta.founderBenefit.value,
                until = beta.founderBenefit.until?.let(ISO::format),
                label = beta.founderBenefit.label,
            ),
            founderUntil = ISO.format(beta.founderUntil),
        ),
        plans = plans,
        limits = limits,
        pricing = pricing,
        flags = flagsAt(now),
        client = ClientConfigDto(
            minimumSupportedVersion = client.minimumSupportedVersion,
            recommendedVersion = client.recommendedVersion,
            forceUpdate = client.forceUpdate,
        ),
    )

    fun toJson(now: Instant = Instant.now()): String =
        CONFIG_JSON.encodeToString(ProductConfigResponse.serializer(), toResponse(now))

    companion object {
        val DEFAULT_END_DATE: Instant = Instant.parse("2027-12-31T23:59:59Z")
        val DEFAULT_FOUNDER_UNTIL: Instant = Instant.parse("2027-12-31T23:59:59Z")
        const val DEFAULT_MIN_VERSION = 2210
        const val DEFAULT_RECOMMENDED_VERSION = 2219
        const val PRO_LIFETIME = "PRO_LIFETIME"
        const val DEFAULT_FOUNDER_KIND = PRO_LIFETIME
        const val DEFAULT_FOUNDER_LABEL = "Glosso Pro gratis para siempre"

        /** The plan whose access the open beta grants to everybody. */
        const val TOP_PLAN = "PERSONALIZED"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
        private val CONFIG_JSON = Json { encodeDefaults = true }

        private fun plan(
            pronunciationAnalysis: String,
            exercises: String,
            analytics: String,
            personalization: String,
            personalizedLearning: Boolean,
        ) = JsonObject(
            linkedMapOf(
                "pronunciation_analysis" to JsonPrimitive(pronunciationAnalysis),
                "exercises" to JsonPrimitive(exercises),
                "analytics" to JsonPrimitive(analytics),
                "personalization" to JsonPrimitive(personalization),
                "personalized_learning" to JsonPrimitive(personalizedLearning),
            ),
        )

        val DEFAULT_PLANS: Map<String, JsonObject> = linkedMapOf(
            "FREE" to plan("LIMITED", "LIMITED", "BASIC", "NONE", false),
            "PRO" to plan("UNLIMITED", "EXTENDED", "ADVANCED", "NONE", false),
            "PERSONALIZED" to plan("UNLIMITED", "UNLIMITED", "ADVANCED", "FULL", true),
        )

        private const val FLAG_PREFIX = "GLOSSO_FLAG_"

        fun fromEnv(env: Map<String, String> = System.getenv()): ProductConfig {
            val mode = when (env.value("GLOSSO_PRODUCT_MODE")?.uppercase()) {
                "FREE" -> ProductMode.FREE
                else -> ProductMode.BETA
            }
            val minVersion = env.value("GLOSSO_MIN_CLIENT_VERSION")?.toIntOrNull() ?: DEFAULT_MIN_VERSION
            return ProductConfig(
                mode = mode,
                beta = BetaConfig(
                    endDate = parseInstant(env, "GLOSSO_BETA_END_DATE") ?: DEFAULT_END_DATE,
                    founderEnabled = parseBoolean(env.value("GLOSSO_BETA_FOUNDER_ENABLED"), default = true),
                    founderBenefit = FounderBenefitConfig(
                        kind = env.value("GLOSSO_BETA_FOUNDER_BENEFIT_KIND") ?: DEFAULT_FOUNDER_KIND,
                        value = env.value("GLOSSO_BETA_FOUNDER_BENEFIT_VALUE")?.toIntOrNull(),
                        until = null,
                        label = env.value("GLOSSO_BETA_FOUNDER_BENEFIT_LABEL") ?: DEFAULT_FOUNDER_LABEL,
                    ),
                    founderUntil = parseInstant(env, "GLOSSO_BETA_FOUNDER_UNTIL") ?: DEFAULT_FOUNDER_UNTIL,
                ),
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
                limits = parseJsonOrNull(env["GLOSSO_LIMITS_JSON"]),
                pricing = parseJsonOrNull(env["GLOSSO_PRICING_JSON"]),
                plans = parsePlans(env["GLOSSO_PLANS_JSON"]),
            )
        }

        /** A blank variable is the same as a missing one: docker compose injects `""` for unset values. */
        private fun Map<String, String>.value(name: String): String? = this[name]?.trim()?.takeIf { it.isNotEmpty() }

        private fun parseInstant(env: Map<String, String>, name: String): Instant? {
            val raw = env.value(name) ?: return null
            return runCatching { Instant.parse(raw) }.getOrElse {
                logger.warn { "$name '$raw' is not an ISO-8601 instant; using the default" }
                null
            }
        }

        /**
         * Per-plan limits and prices are served as the operator writes them, so
         * the shape can be fixed from configuration without an app release and
         * without the server guessing any numbers. Unset (or unparseable) means
         * "not decided yet" and is served as null.
         */
        private fun parseJsonOrNull(raw: String?): JsonElement? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { Json.parseToJsonElement(text) }.getOrNull()
        }

        /**
         * `GLOSSO_PLANS_JSON` overrides the entitlement matrix key by key on top
         * of the defaults: a value can change and a new entitlement can be added,
         * but a key the operator does not mention keeps its default, so an old
         * app that expects the four original keys never sees them disappear.
         * Only the three known plans exist; any other top-level key is ignored.
         */
        private fun parsePlans(raw: String?): Map<String, JsonObject> {
            val overrides = parseJsonOrNull(raw) as? JsonObject ?: run {
                if (!raw.isNullOrBlank()) logger.warn { "GLOSSO_PLANS_JSON is not a JSON object; using the defaults" }
                return DEFAULT_PLANS
            }
            return DEFAULT_PLANS.mapValues { (name, defaults) ->
                val override = overrides[name] as? JsonObject ?: return@mapValues defaults
                JsonObject(defaults + override.filterValues { it !is JsonNull })
            }
        }

        private fun parseFlags(env: Map<String, String>): Map<String, Boolean> {
            val flags = linkedMapOf(
                "pro" to false,
                "personalized" to false,
                "accounts" to false,
                "adaptive_exercises" to false,
                "advanced_analytics" to false,
            )
            val overrides = env.entries
                .filter { it.key.startsWith(FLAG_PREFIX) && it.key.length > FLAG_PREFIX.length && it.value.isNotBlank() }
                .associate { it.key.removePrefix(FLAG_PREFIX).lowercase() to parseBoolean(it.value, default = false) }
            for ((flag, enabled) in overrides) {
                if (flag in flags) flags[flag] = enabled
            }
            overrides.keys.filter { it !in flags }.sorted().forEach { flags[it] = overrides.getValue(it) }
            return flags
        }

        private fun parseBoolean(raw: String?, default: Boolean): Boolean = when (raw?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }
}
