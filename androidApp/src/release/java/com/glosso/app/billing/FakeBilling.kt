package com.glosso.app.billing

/**
 * Release has no fake. The symbol exists only so [BillingProvider] compiles; it
 * always returns null and never references a fake implementation.
 */
internal fun debugFakeBilling(): SubscriptionRepository? = null
