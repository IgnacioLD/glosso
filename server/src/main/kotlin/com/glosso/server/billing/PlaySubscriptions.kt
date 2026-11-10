package com.glosso.server.billing

/**
 * One subscription line item as returned by the Google Play Developer API
 * (`purchases.subscriptionsv2.get`). A multi-product subscription has several,
 * but the Glosso subscription has exactly one.
 */
data class PlayLineItem(
    val productId: String,
    val expiryTimeMillis: Long?,
    val autoRenewing: Boolean,
    val offerId: String?,
)

/**
 * The slice of a Play subscription purchase that entitlement needs, kept as plain
 * values (not the JSON DTO) so the mapping is testable without the network.
 */
data class PlaySubscription(
    val state: String,
    val lineItems: List<PlayLineItem>,
    val cancelTimeMillis: Long?,
    val acknowledgeState: String?,
)

/**
 * Raised when Play cannot answer (network, credentials, 5xx), so the caller must
 * fail closed instead of assuming Pro.
 */
class PlayApiException(message: String, val statusCode: Int? = null) : Exception(message)

/** Raised when the server has no service account, so verification cannot run. */
class BillingNotConfiguredException(message: String) : Exception(message)

/**
 * Reads a subscription purchase from the Play Developer API. Returns null when
 * Play reports the token as unknown (a verdict: not entitled); throws
 * [PlayApiException] when the API is unreachable or rejects our credentials.
 */
interface PlaySubscriptionsClient {
    suspend fun getSubscription(packageName: String, purchaseToken: String): PlaySubscription?

    /** False when there is no service account, so verification cannot run. */
    val configured: Boolean
}

/** Used when billing is not configured: every verification says "cannot verify". */
object UnavailablePlaySubscriptionsClient : PlaySubscriptionsClient {
    override val configured: Boolean = false

    override suspend fun getSubscription(packageName: String, purchaseToken: String): PlaySubscription =
        throw BillingNotConfiguredException(
            "Play verification is not configured (no service account). See docs/BILLING-E2E.md.",
        )
}
