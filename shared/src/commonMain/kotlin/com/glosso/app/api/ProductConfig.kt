package com.glosso.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The product configuration the server owns (`GET /v1/config`), frozen in
 * docs/PRODUCT-CONFIG-CONTRACT.md. The client only reads the feature flags and
 * the client version policy, and never writes any of it back. Other keys the
 * server still sends for older app versions are ignored.
 *
 * Every field has a default so a server that predates a key, or a body missing
 * it, decodes into something safe instead of throwing.
 */
@Serializable
data class ProductConfig(
    val schema: Int = 1,
    val flags: Map<String, Boolean> = emptyMap(),
    val client: ClientConfig = ClientConfig(),
) {
    /** A feature flag is on only when the server says so; a missing key is false. */
    fun flag(name: String): Boolean = flags[name] == true
}

/** The version policy. The client only ever warns; it never blocks. */
@Serializable
data class ClientConfig(
    @SerialName("minimumSupportedVersion") val minimumSupportedVersion: Int = 0,
    @SerialName("recommendedVersion") val recommendedVersion: Int = 0,
    /**
     * Decoded but intentionally unused: the version policy is only ever a soft,
     * dismissible notice ([com.glosso.app.product.VersionNotice]), never a hard gate.
     */
    val forceUpdate: Boolean = false,
)

/** This device's account (`GET /v1/me`), authenticated by `X-Device-Id`. */
@Serializable
data class AccountState(
    /**
     * The external accounts this account is linked to (e.g. `google`). Null
     * when the server does not report it, which says nothing either way.
     */
    val linkedAccounts: List<String>? = null,
)
