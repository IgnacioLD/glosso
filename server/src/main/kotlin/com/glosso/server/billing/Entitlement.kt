package com.glosso.server.billing

/** The subscription states the server can decide, mirroring the client enum. */
enum class EntitlementStatus {
    TRIAL,
    ACTIVE,
    GRACE,
    CANCELED,
    PENDING,
    NONE,
    ;

    /** True when the learner should be treated as Pro right now. */
    val isEntitled: Boolean
        get() = this == TRIAL || this == ACTIVE || this == GRACE || this == CANCELED
}

/** The verdict the server reached for one purchase. */
data class Entitlement(
    val status: EntitlementStatus,
    val entitled: Boolean,
    val expiresAtMillis: Long?,
    /** Play's `autoRenewing` for the line item; false when Play did not say. */
    val autoRenew: Boolean = false,
)

/**
 * Maps a Play subscription snapshot to the server's entitlement model. Kept pure
 * (no clock, no network) so every Play state has a test.
 *
 * Fail-closed rules:
 *  - an unknown or missing token, or a product not in the purchase, is NONE;
 *  - ON_HOLD (payment failed and the grace period is over), PAUSED and PENDING
 *    grant nothing;
 *  - CANCELED is entitled only while the paid period is still in the future;
 *  - an unknown Play state grants nothing rather than guessing.
 *
 * `SUBSCRIPTION_STATE_ACTIVE` is TRIAL only when the configured trial offer is
 * the one on the line item; without that config both are ACTIVE. Both grant
 * entitlement, so the distinction is cosmetic.
 */
object SubscriptionVerifier {

    fun verify(
        purchase: PlaySubscription?,
        requestedProductId: String,
        trialOfferId: String?,
        nowMillis: Long,
    ): Entitlement {
        if (purchase == null) return Entitlement(EntitlementStatus.NONE, false, null)

        val line = purchase.lineItems.firstOrNull { it.productId == requestedProductId }
            ?: return Entitlement(EntitlementStatus.NONE, false, null)
        val expiry = line.expiryTimeMillis

        return when (purchase.state) {
            "SUBSCRIPTION_STATE_ACTIVE" -> {
                val trial = trialOfferId != null && line.offerId == trialOfferId
                Entitlement(
                    status = if (trial) EntitlementStatus.TRIAL else EntitlementStatus.ACTIVE,
                    entitled = true,
                    expiresAtMillis = expiry,
                    autoRenew = line.autoRenewing,
                )
            }
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" ->
                Entitlement(EntitlementStatus.GRACE, true, expiry, line.autoRenewing)
            "SUBSCRIPTION_STATE_CANCELED" ->
                Entitlement(
                    EntitlementStatus.CANCELED,
                    expiry != null && expiry > nowMillis,
                    expiry,
                    line.autoRenewing,
                )
            "SUBSCRIPTION_STATE_PENDING",
            "SUBSCRIPTION_STATE_ON_HOLD",
            "SUBSCRIPTION_STATE_PAUSED",
            -> Entitlement(EntitlementStatus.PENDING, false, null)
            "SUBSCRIPTION_STATE_EXPIRED" -> Entitlement(EntitlementStatus.NONE, false, null)
            else -> Entitlement(EntitlementStatus.NONE, false, null)
        }
    }
}
