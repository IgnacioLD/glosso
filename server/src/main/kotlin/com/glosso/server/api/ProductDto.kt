package com.glosso.server.api

import kotlinx.serialization.Serializable

/**
 * The shape revision of `GET /v1/config`. Bumped only on a breaking change:
 * additive keys are safe because every client decodes with unknown keys ignored.
 */
const val PRODUCT_CONFIG_SCHEMA = 1

/**
 * `GET /v1/config`: flags and the client version policy. The server is the
 * authority; the app never writes any of it. See docs/PRODUCT-CONFIG-CONTRACT.md.
 */
@Serializable
data class ProductConfigResponse(
    val schema: Int = PRODUCT_CONFIG_SCHEMA,
    /** Legacy: app builds up to 2.6 lock content unless the phase reads as an open beta. */
    val mode: String = "BETA",
    /** Legacy: see [mode]. */
    val beta: LegacyBetaDto = LegacyBetaDto(),
    /** A missing flag is false on the client; new flags need no app release. */
    val flags: Map<String, Boolean>,
    val client: ClientConfigDto,
)

@Serializable
data class LegacyBetaDto(
    val active: Boolean = true,
)

@Serializable
data class ClientConfigDto(
    val minimumSupportedVersion: Int,
    val recommendedVersion: Int,
    val forceUpdate: Boolean,
)

/** `GET /v1/me`: the server-computed identity snapshot for one device. */
@Serializable
data class MeResponse(
    /**
     * The external accounts (providers, e.g. `google`) this account is linked
     * to. A device whose account moved elsewhere gets a fresh anonymous user
     * with none, so the app forgets any local "linked to" state.
     */
    val linkedAccounts: List<String> = emptyList(),
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
