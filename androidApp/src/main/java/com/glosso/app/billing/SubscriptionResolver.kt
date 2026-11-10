package com.glosso.app.billing

/**
 * The fields of a Play purchase that entitlement resolution needs, so the logic
 * can be tested without the Play client. [PlaySubscriptionRepository] maps a
 * `com.android.billingclient.api.Purchase` to this value.
 */
data class PurchaseView(
    val productId: String,
    val purchased: Boolean,
    val pending: Boolean,
    val autoRenewing: Boolean,
    val purchaseTimeMillis: Long,
    val acknowledged: Boolean,
    val token: String,
)

/** The outcome of choosing the purchase that decides entitlement. */
sealed interface PurchaseSelection {
    /** A paid purchase. [alsoPending] is another purchase still pending, if any. */
    data class Paid(
        val purchase: PurchaseView,
        val alsoPending: PurchaseView?,
    ) : PurchaseSelection

    /** Only a deferred (pending) payment: no entitlement until it completes. */
    data class Deferred(val purchase: PurchaseView) : PurchaseSelection

    /** Nothing relevant on the account. */
    data object None : PurchaseSelection
}

/**
 * Pure entitlement resolution, kept out of the BillingClient so it can be tested.
 *
 * It encodes two rules that must not regress:
 *  - a PURCHASED purchase always wins over a PENDING one, so a paid purchase is
 *    never masked and is always acknowledged;
 *  - TRIAL and ACTIVE can only be told apart once the offer's pricing phases are
 *    known, so [statusFor] returns null while they are missing and the caller
 *    keeps the last known status and recomputes when the details arrive.
 */
object SubscriptionResolver {

    fun select(purchases: List<PurchaseView>): PurchaseSelection {
        val purchased = purchases.firstOrNull { it.purchased }
        if (purchased != null) {
            return PurchaseSelection.Paid(purchased, purchases.firstOrNull { it.pending })
        }
        val pending = purchases.firstOrNull { it.pending }
        return if (pending != null) PurchaseSelection.Deferred(pending) else PurchaseSelection.None
    }

    /**
     * TRIAL, ACTIVE or CANCELED for a paid purchase, or null when [plan] is not
     * loaded yet. Without the pricing phases a trial cannot be distinguished from
     * a paid subscription, so the caller must wait rather than guess ACTIVE.
     */
    fun statusFor(
        purchase: PurchaseView,
        plan: SubscriptionPlan?,
        nowMillis: Long = System.currentTimeMillis(),
    ): SubscriptionStatus? {
        if (plan == null) return null
        val trialEnd = plan.trialEndMillis(purchase.purchaseTimeMillis)
        return when {
            trialEnd != null && nowMillis < trialEnd -> SubscriptionStatus.TRIAL
            !purchase.autoRenewing -> SubscriptionStatus.CANCELED
            else -> SubscriptionStatus.ACTIVE
        }
    }
}
