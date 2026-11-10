package com.glosso.app.billing

import com.glosso.app.platform.PlatformActivity
import kotlinx.coroutines.flow.StateFlow

/**
 * The subscription port the app depends on. `PlaySubscriptionRepository` (Android)
 * is the real Google Play Billing implementation; a fake in the debug source set stands
 * in for development and tests, because Google ships no official test double.
 *
 * Everything is exposed as state the UI can observe; the implementation owns the
 * BillingClient, its connection lifecycle and its error handling, so no screen
 * ever talks to the store directly.
 */
interface SubscriptionRepository {

    /** The current entitlement state. Never null; starts at NONE. */
    val status: StateFlow<SubscriptionStatus>

    /** The offer Play returned, or null until product details load. */
    val plan: StateFlow<SubscriptionPlan?>

    /** True while details or purchases are being fetched. */
    val loading: StateFlow<Boolean>

    /** A user-facing error, or null. A failure never crashes the app. */
    val error: StateFlow<String?>

    /**
     * Connects the client, loads the offer and restores any existing purchase.
     * Safe to call more than once; the second call is a no-op while connected.
     */
    fun start()

    /** Re-queries product details and purchases. [restore] also restores. */
    fun refresh(restore: Boolean = true)

    /** Launches the store's purchase flow for the loaded offer, from [activity]. */
    fun subscribe(activity: PlatformActivity)

    /** Clears the last error so the UI can dismiss it. */
    fun clearError()
}
