package com.glosso.server.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The shape revision of `GET /v1/config`. Bumped only on a breaking change:
 * additive keys are safe because every client decodes with unknown keys ignored.
 */
const val PRODUCT_CONFIG_SCHEMA = 1

/**
 * `GET /v1/config`: the whole product configuration the client consumes. The
 * server is the authority (phase, beta dates, plans, flags, minimum version);
 * the app never writes any of it. See docs/PRODUCT-CONFIG-CONTRACT.md.
 */
@Serializable
data class ProductConfigResponse(
    val schema: Int = PRODUCT_CONFIG_SCHEMA,
    /** `BETA` or `FREE`: the active phase, not a subscription tier. */
    val mode: String,
    val beta: BetaDto,
    /**
     * What each plan includes, keyed by plan. Values are the server's vocabulary
     * and configurable (`GLOSSO_PLANS_JSON`); keys are never removed.
     */
    val plans: Map<String, JsonObject>,
    /** Per-plan limits, or null while undecided. */
    val limits: JsonElement? = null,
    /** Prices, or null while undecided: the UI must not sell a null price. */
    val pricing: JsonElement? = null,
    /** A missing flag is false on the client; new flags need no app release. */
    val flags: Map<String, Boolean>,
    val client: ClientConfigDto,
)

@Serializable
data class BetaDto(
    val active: Boolean,
    /** ISO-8601 UTC. The single source of the date; the app only formats it. */
    val endDate: String,
    val founderEnabled: Boolean,
    val founderBenefit: FounderBenefitDto,
    /** ISO-8601 UTC. Altas on or before it are Beta Founders; independent of [endDate]. */
    val founderUntil: String,
)

/**
 * The Beta Founder benefit. `value` is null while undecided, and `= null` means
 * "do not sell yet". `until` is null while the benefit does not expire.
 */
@Serializable
data class FounderBenefitDto(
    val kind: String? = null,
    val value: Int? = null,
    val until: String? = null,
    val label: String? = null,
)

@Serializable
data class ClientConfigDto(
    val minimumSupportedVersion: Int,
    val recommendedVersion: Int,
    val forceUpdate: Boolean,
)

/**
 * `GET /v1/me`: the server-computed identity snapshot for one device. `plan` and
 * `betaFounder` are independent: a Beta Founder gets `PRO` content for free and
 * keeps the status even on a higher paid plan. The client caches it for the UI;
 * access is still decided server-side.
 */
@Serializable
data class MeResponse(
    val plan: String,
    val status: String,
    val betaFounder: Boolean,
    val betaFounderSince: String? = null,
    val betaFounderBenefit: String? = null,
    val expiresAt: String? = null,
    /** When this founder's benefit ends; null while it does not expire. The status itself never ends. */
    val betaFounderUntil: String? = null,
    /**
     * Where [plan] comes from: `LIFETIME` (a one-time grant with no renewal or
     * expiry, e.g. the Beta Founder PRO), `SUBSCRIPTION` or `FREE`. With
     * `LIFETIME` the UI shows permanent access, never renewal or cancellation.
     * [status] and [expiresAt] describe the plan only when it is `SUBSCRIPTION`.
     */
    val planSource: String = "FREE",
    /** The non-revoked lifetime grants of this user. */
    val lifetimeGrants: List<LifetimeGrantDto> = emptyList(),
    /** True while the open beta grants this device full access regardless of [plan]. */
    val betaAccess: Boolean = false,
    /** The effective entitlements the server grants right now (the plan's, or full access in beta). */
    val entitlements: JsonObject = JsonObject(emptyMap()),
    /** The paid subscription behind [plan], or null when none is in force. */
    val subscription: SubscriptionDto? = null,
    /**
     * The external accounts (providers, e.g. `google`) this account is linked
     * to. A device whose account moved elsewhere gets a fresh anonymous user
     * with none, so the app forgets any local "linked to" state.
     */
    val linkedAccounts: List<String> = emptyList(),
)

/** A one-time plan grant: no expiry, no renewal, no grace period. */
@Serializable
data class LifetimeGrantDto(
    val plan: String,
    val source: String,
    val grantedAt: String,
)

/**
 * A paid plan in force. `autoRenew=false` with `status=CANCELED` is a cancelled
 * subscription still paid through `expiresAt`; `GRACE` is a failed renewal still
 * entitled. Restoring on a new device is a new `POST /v1/billing/verify`.
 */
@Serializable
data class SubscriptionDto(
    /** TRIAL, ACTIVE, GRACE or CANCELED: the subscription's own state. */
    val status: String,
    val provider: String? = null,
    val source: String,
    val startedAt: String,
    val expiresAt: String? = null,
    val autoRenew: Boolean,
)

/**
 * `POST /v1/auth/google/nonce`: a short-lived nonce bound to this device, to be
 * passed to Credential Manager so Google signs it into the ID token.
 */
@Serializable
data class GoogleSignInNonceResponse(
    val nonce: String,
)

/**
 * `POST /v1/auth/google`: the Google ID token of the account the learner chose
 * on the device. Answered with the same snapshot as `GET /v1/me`.
 */
@Serializable
data class GoogleSignInRequest(
    val idToken: String,
)
