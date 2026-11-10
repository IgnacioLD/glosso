package com.glosso.app.billing

/**
 * The subscription state the rest of the app reads. It is a pure value so the UI
 * and the access policy never touch BillingClient directly.
 *
 * Entitlement rule, in one place: [isEntitled] is true while the learner still
 * has access. CANCELED stays entitled on purpose: canceling stops the renewal but
 * Play keeps the purchase until the paid period ends, so the learner keeps what
 * they paid for. When Play finally drops the purchase, the repository reports
 * NONE. GRACE is set by server verification (RTDN) or the fake; the client cannot
 * see a grace period on its own.
 */
enum class SubscriptionStatus {
    /** No purchase on this account. */
    NONE,

    /** Free trial running. */
    TRIAL,

    /** Paid subscription renewing. */
    ACTIVE,

    /** Payment failed; still within the grace period, access continues. */
    GRACE,

    /** Canceled by the user; access continues until the period ends. */
    CANCELED,

    /** Deferred payment (e.g. cash); access is granted only once it completes. */
    PENDING,
    ;

    /** True when the learner should be treated as Pro right now. */
    val isEntitled: Boolean
        get() = this == TRIAL || this == ACTIVE || this == GRACE || this == CANCELED
}
