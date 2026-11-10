package com.glosso.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The product configuration the server owns (`GET /v1/config`), frozen in
 * docs/PRODUCT-CONFIG-CONTRACT.md. Everything here is read-only for the client:
 * the phase, the beta window, the three plans, the limits, the prices, the
 * feature flags and the client version policy all come from the server, and the
 * app never writes any of it back.
 *
 * Every field has a conservative default so a server that predates a key, or a
 * body missing it, decodes into something safe instead of throwing.
 */
@Serializable
data class ProductConfig(
    val schema: Int = 1,
    val mode: String = MODE_BETA,
    val beta: BetaConfig = BetaConfig(),
    val plans: Map<String, PlanFeatures> = emptyMap(),
    /**
     * Null while the server has not decided any limit (it serves `"limits": null`).
     * A null here is not "no limits declared": the whole body is kept, and the
     * absent map behaves exactly like an empty one when read.
     */
    val limits: Map<String, PlanLimits>? = null,
    /** Null while no price is decided (`"pricing": null`); see [limits]. */
    val pricing: Map<String, PlanPricing?>? = null,
    val flags: Map<String, Boolean> = emptyMap(),
    val client: ClientConfig = ClientConfig(),
) {
    companion object {
        const val MODE_BETA = "BETA"
        const val MODE_FREE = "FREE"

        /**
         * What the app assumes when neither the server nor the cache has ever
         * answered. During the beta this is the honest state: the beta is open,
         * so nothing is locked and nothing is sold, and the absence of an end
         * date just means the UI says "during the beta" instead of a date. It is
         * fail-open, never a wall invented by the client.
         */
        val BetaFallback = ProductConfig(
            mode = MODE_BETA,
            beta = BetaConfig(active = true),
        )
    }

    /** True while the product is in its open beta phase. */
    val isBeta: Boolean get() = mode.equals(MODE_BETA, ignoreCase = true)

    /** A feature flag is on only when the server says so; a missing key is false. */
    fun flag(name: String): Boolean = flags[name] == true

    /** The declared entitlements of a plan, or null when the plan is not in the config. */
    fun featuresOf(plan: String): PlanFeatures? = plans[plan]

    /** The published price of a plan; null (or nulls inside) means "not for sale yet". */
    fun pricingOf(plan: String): PlanPricing? = pricing?.get(plan)
}

@Serializable
data class BetaConfig(
    val active: Boolean = false,
    /** ISO-8601 UTC; the only source of the beta end date in the whole app. */
    val endDate: String? = null,
    val founderEnabled: Boolean = false,
    val founderBenefit: FounderBenefit? = null,
)

@Serializable
data class FounderBenefit(
    val kind: String? = null,
    val value: Double? = null,
    val until: String? = null,
    val label: String? = null,
)

/** What a plan includes. The strings are the server's vocabulary, not the app's. */
@Serializable
data class PlanFeatures(
    @SerialName("pronunciation_analysis") val pronunciationAnalysis: String = "",
    val exercises: String = "",
    val analytics: String = "",
    val personalization: String = "",
)

/** Optional per-plan limits; a limit the server does not declare does not apply. */
@Serializable
data class PlanLimits(
    @SerialName("pronunciation_analysis_per_day") val pronunciationAnalysisPerDay: Int? = null,
    @SerialName("history_days") val historyDays: Int? = null,
)

/**
 * A plan's price. Any field may be null: the contract says null means the price
 * is not decided, and the UI then shows no price and sells nothing.
 */
@Serializable
data class PlanPricing(
    val monthly: Double? = null,
    val yearly: Double? = null,
    val currency: String? = null,
) {
    /** True only when at least one real amount is published. */
    val published: Boolean get() = (monthly != null && monthly > 0) || (yearly != null && yearly > 0)
}

/** The version policy. The client only ever warns; it never blocks during the beta. */
@Serializable
data class ClientConfig(
    @SerialName("minimumSupportedVersion") val minimumSupportedVersion: Int = 0,
    @SerialName("recommendedVersion") val recommendedVersion: Int = 0,
    /**
     * Decoded but intentionally unused: during the beta the version policy is
     * only ever a soft, dismissible notice (`AccessPolicy.versionNotice`), never
     * a hard gate. It is kept here so the day the product leaves beta the server
     * flag is already part of the model.
     */
    val forceUpdate: Boolean = false,
)

/**
 * The effective access of one device (`GET /v1/me`), authenticated by
 * `X-Device-Id`. [plan] is the server's decision; [betaFounder] is an orthogonal
 * status, never nested into the plan. [betaFounderBenefit] is the benefit label
 * the server derives from `beta.founderBenefit.label`, sent as plain text (not
 * the config object).
 */
@Serializable
data class AccountState(
    val plan: String = "FREE",
    val status: String = "NONE",
    val betaFounder: Boolean = false,
    val betaFounderSince: String? = null,
    val betaFounderBenefit: String? = null,
    val expiresAt: String? = null,
    /**
     * The external accounts this account is linked to (e.g. `google`). Null
     * when the server does not report it, which says nothing either way.
     */
    val linkedAccounts: List<String>? = null,
)
