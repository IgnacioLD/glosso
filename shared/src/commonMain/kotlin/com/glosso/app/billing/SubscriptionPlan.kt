package com.glosso.app.billing

/**
 * One subscription offer as Play returned it, already reduced to what the paywall
 * needs. Nothing here is hardcoded: the price and the free-trial period come from
 * Play's [ProductDetails] and its pricing phases, so the copy can never drift from
 * the store. The wording of the periods is built at the UI from these ISO values,
 * so it follows the interface language.
 *
 * [offerToken] identifies the exact base plan plus offer (including the free-trial
 * pricing phase). The purchase flow must use it, or Play would charge the plain
 * base plan with no trial.
 */
data class SubscriptionPlan(
    val productId: String,
    val title: String,
    val description: String,
    val offerToken: String,
    val basePlanId: String,
    val offerId: String?,
    /** Formatted recurring price, e.g. "6,99 €" (Play's localized string). */
    val priceFormatted: String,
    /** ISO-8601 period of the recurring charge, e.g. "P1M", when known. */
    val recurringPeriodIso: String? = null,
    /** ISO-8601 trial period, e.g. "P1M", or null when the offer has no trial. */
    val freeTrialPeriod: String?,
) {
    val hasFreeTrial: Boolean get() = freeTrialPeriod != null

    /** The end instant of a trial that started at [purchaseTimeMillis]. */
    fun trialEndMillis(purchaseTimeMillis: Long): Long? =
        BillingPeriods.endMillis(purchaseTimeMillis, freeTrialPeriod)
}
