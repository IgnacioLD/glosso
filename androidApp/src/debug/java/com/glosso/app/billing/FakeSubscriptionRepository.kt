package com.glosso.app.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A deterministic stand-in for Play Billing, used by debug builds and tests.
 *
 * Google does not ship an official test double, and a real purchase can only be
 * exercised from a Play testing track with license testers, so this is the only
 * way to walk the whole paywall and gate flow locally. It never talks to Google
 * and never charges anything.
 *
 * The plan it exposes is a development placeholder, not a real price. A release
 * build always uses [PlaySubscriptionRepository].
 */
class FakeSubscriptionRepository(
    private val planValue: SubscriptionPlan = devPlan,
    initialStatus: SubscriptionStatus = SubscriptionStatus.NONE,
) : SubscriptionRepository {

    private val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<SubscriptionStatus> = _status.asStateFlow()

    private val _plan = MutableStateFlow<SubscriptionPlan?>(planValue)
    override val plan: StateFlow<SubscriptionPlan?> = _plan.asStateFlow()

    private val _loading = MutableStateFlow(false)
    override val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    override fun start() {
        _loading.value = false
    }

    override fun refresh(restore: Boolean) {
        _loading.value = false
        _error.value = null
    }

    /** A fake purchase succeeds instantly and starts the free trial. */
    override fun subscribe(activity: Activity) {
        if (_status.value == SubscriptionStatus.NONE) {
            _status.value = if (planValue.hasFreeTrial) SubscriptionStatus.TRIAL
            else SubscriptionStatus.ACTIVE
        }
    }

    override fun clearError() {
        _error.value = null
    }

    // Test and debug hooks. Not part of the production interface.

    /** Simulates completing a purchase (the CTA in a debug build does this). */
    fun simulateSubscribe() {
        if (_status.value == SubscriptionStatus.NONE) {
            _status.value = if (planValue.hasFreeTrial) SubscriptionStatus.TRIAL
            else SubscriptionStatus.ACTIVE
        }
    }

    /** Simulates the trial elapsing into a paid, renewing subscription. */
    fun simulateActivate() {
        _status.value = SubscriptionStatus.ACTIVE
    }

    /** Simulates cancelling; access continues, like Play's real behavior. */
    fun simulateCancel() {
        _status.value = SubscriptionStatus.CANCELED
    }

    /** Simulates a payment problem Play put into grace. */
    fun simulateGrace() {
        _status.value = SubscriptionStatus.GRACE
    }

    /** Simulates a deferred (pending) payment. */
    fun simulatePending() {
        _status.value = SubscriptionStatus.PENDING
    }

    /** Simulates a network failure, to exercise the error path. */
    fun simulateError(message: String = "No se pudo conectar con Google Play.") {
        _error.value = message
    }

    /** Back to a fresh install. */
    fun reset() {
        _status.value = SubscriptionStatus.NONE
        _error.value = null
        _loading.value = false
    }

    companion object {
        /**
         * Placeholder offer for development only. Price and trial are made up; the
         * real values always come from Play.
         */
        val devPlan = SubscriptionPlan(
            // The published identity's product id (see docs/BILLING.md); it only
            // shapes the manage-subscription deep link in debug.
            productId = "me.shirobyte42.glosso.pro",
            title = "Glosso Pro (desarrollo)",
            description = "Plan de prueba del modo fake, no es un precio real.",
            offerToken = "fake-offer-token",
            basePlanId = "fake-base-plan",
            offerId = "fake-trial-offer",
            priceFormatted = "6,99 €",
            recurringPeriodIso = "P1M",
            freeTrialPeriod = "P1M",
        )
    }
}
