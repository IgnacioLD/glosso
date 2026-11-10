package com.glosso.app.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.glosso.app.Analytics
import com.glosso.app.BuildConfig
import com.glosso.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real Google Play Billing implementation.
 *
 * It owns the [BillingClient] lifecycle: connecting (and reconnecting) the
 * service, restoring purchases on launch and on every resume, loading the offer so
 * the paywall shows Play's own price and trial, launching the purchase flow with
 * the offer token (never the bare base plan), acknowledging the purchase, and
 * surfacing network or configuration failures as state instead of crashing.
 *
 * Honesty note: none of this can be exercised end to end locally. A real purchase
 * requires the app installed from a Play testing track with license testers. In
 * debug, a fake in the debug source set stands in.
 */
class PlaySubscriptionRepository(
    context: Context,
    private val productId: String = BuildConfig.SUBSCRIPTION_PRODUCT_ID,
) : SubscriptionRepository {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(SubscriptionStatus.NONE)
    override val status: StateFlow<SubscriptionStatus> = _status.asStateFlow()

    private val _plan = MutableStateFlow<SubscriptionPlan?>(null)
    override val plan: StateFlow<SubscriptionPlan?> = _plan.asStateFlow()

    private val _loading = MutableStateFlow(false)
    override val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var released = false

    @Volatile
    private var connecting = false

    private var reconnectAttempts = 0

    @Volatile
    private var productDetails: ProductDetails? = null

    // The last purchase Play returned, kept so the status can be recomputed once
    // the offer phases load (TRIAL vs ACTIVE needs them).
    @Volatile
    private var lastPaid: PurchaseView? = null

    @Volatile
    private var lastPending: PurchaseView? = null

    // Purchases this process already sent to acknowledge, so a re-query does not
    // fire the same acknowledgement repeatedly. Main-thread only.
    private val acknowledgeSent = mutableSetOf<String>()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            val reason = if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                "user_canceled"
            } else {
                "code_${result.responseCode}"
            }
            Analytics.track(
                "purchase_failed",
                mapOf("product_id" to _plan.value?.productId, "reason" to reason),
            )
        }
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                if (purchases != null) processPurchases(purchases)

            BillingClient.BillingResponseCode.USER_CANCELED -> _error.value = null

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _error.value = null
                refresh(restore = true)
            }

            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            -> {
                _error.value = appContext.getString(R.string.billing_error_offline)
                scheduleReconnect()
            }

            else -> _error.value = messageFor(result)
        }
    }

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        // Required even though this app sells subscriptions: Play needs the client
        // to declare how it will handle deferred (pending) payments.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .enableAutoServiceReconnection()
        .build()

    override fun start() {
        if (released) return
        if (billingClient.isReady) {
            onConnected()
            return
        }
        if (connecting) return
        connecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectAttempts = 0
                    _error.value = null
                    onConnected()
                } else {
                    _error.value = messageFor(result)
                    scheduleReconnect()
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                if (!released) scheduleReconnect()
            }
        })
    }

    private fun onConnected() {
        loadProductDetails()
        queryPurchases()
    }

    /**
     * Loads the product and its offers. The offer that carries a free-trial
     * pricing phase wins, so the flow uses its offer token and the paywall shows
     * the trial Play actually configured.
     */
    private fun loadProductDetails() {
        _loading.value = true
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            _loading.value = false
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _error.value = messageFor(result)
                if (isRetryable(result.responseCode)) scheduleReconnect()
                return@queryProductDetailsAsync
            }
            val details = queryResult.productDetailsList.firstOrNull { it.productId == productId }
            productDetails = details
            if (details == null) {
                _plan.value = null
                _error.value = appContext.getString(R.string.billing_error_not_configured)
                return@queryProductDetailsAsync
            }
            val plan = details.toPlan()
            _plan.value = plan
            if (plan == null) {
                _error.value = appContext.getString(R.string.billing_error_no_offers)
                return@queryProductDetailsAsync
            }
            // The offer phases are now known: recover any error and recompute the
            // status, so a purchase seen before this moment is labelled right.
            _error.value = null
            recomputeStatus()
        }
    }

    override fun refresh(restore: Boolean) {
        if (released) return
        // A retry by the user: drop the stale error immediately.
        _error.value = null
        if (!billingClient.isReady) {
            start()
            return
        }
        if (_plan.value == null) loadProductDetails()
        queryPurchases()
    }

    /** The restore path: whatever Play holds for this account becomes the status. */
    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    _error.value = null
                    processPurchases(purchases)
                }

                else -> {
                    _error.value = messageFor(result)
                    if (isRetryable(result.responseCode)) scheduleReconnect()
                }
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val views = purchases.map { it.toView() }.filter { it.productId == productId }

        when (val selection = SubscriptionResolver.select(views)) {
            is PurchaseSelection.Paid -> {
                // A PURCHASED purchase wins over a pending one: it is the entitlement
                // now, and it must be acknowledged even if a pending purchase exists.
                lastPaid = selection.purchase
                lastPending = selection.alsoPending
                val paid = selection.purchase
                if (!paid.acknowledged && acknowledgeSent.add(paid.token)) {
                    acknowledge(paid.token)
                }
                recomputeStatus()
            }

            is PurchaseSelection.Deferred -> {
                // Deferred payment: no entitlement until it completes. It is not
                // acknowledged (there is nothing to acknowledge yet).
                lastPaid = null
                lastPending = selection.purchase
                _status.value = SubscriptionStatus.PENDING
            }

            PurchaseSelection.None -> {
                lastPaid = null
                lastPending = null
                _status.value = SubscriptionStatus.NONE
            }
        }
    }

    /**
     * Recomputes the status from the last purchases. Waits for the offer phases
     * (TRIAL vs ACTIVE) instead of guessing ACTIVE when the plan is not loaded.
     */
    private fun recomputeStatus() {
        val paid = lastPaid
        if (paid == null) {
            _status.value = if (lastPending != null) {
                SubscriptionStatus.PENDING
            } else {
                SubscriptionStatus.NONE
            }
            return
        }
        val plan = _plan.value ?: return
        SubscriptionResolver.statusFor(paid, plan)?.let { _status.value = it }
    }

    /** Acknowledge or Play refunds the purchase after three days. */
    private fun acknowledge(token: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(token)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // Let the next query retry the acknowledgement.
                acknowledgeSent.remove(token)
                _error.value = appContext.getString(R.string.billing_error_confirm)
                handler.postDelayed({
                    if (!released) refresh(restore = true)
                }, 5_000)
            }
        }
    }

    override fun subscribe(activity: Activity) {
        if (released) return
        if (!billingClient.isReady) {
            _error.value = appContext.getString(R.string.billing_error_connecting)
            start()
            return
        }
        val details = productDetails
        val plan = _plan.value
        if (details == null || plan == null) {
            _error.value = appContext.getString(R.string.billing_error_not_available_yet)
            loadProductDetails()
            return
        }

        // Only the offer token: it selects the base plan plus the free-trial offer.
        // No replacement/upgrade parameters are sent, so this flow can never change
        // an existing subscription's proration by accident.
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(plan.offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            Analytics.track("purchase_started", mapOf("product_id" to plan.productId, "plan" to plan.basePlanId))
            _error.value = null
        } else {
            _error.value = messageFor(result)
        }
    }

    override fun clearError() {
        _error.value = null
    }

    /** Stops the client and cancels pending retries. Call from app shutdown. */
    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        runCatching { billingClient.endConnection() }
    }

    private fun scheduleReconnect() {
        if (released) return
        val attempt = reconnectAttempts
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            _error.value = appContext.getString(R.string.billing_error_reconnect)
            return
        }
        reconnectAttempts = attempt + 1
        val delayMillis = (1L shl attempt.coerceAtMost(5)) * 1_000L
        handler.postDelayed({
            if (!released && !billingClient.isReady) start()
        }, delayMillis)
    }

    private fun isRetryable(code: Int): Boolean = when (code) {
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        -> true

        else -> false
    }

    private fun messageFor(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            appContext.getString(R.string.billing_error_network)

        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ->
            appContext.getString(R.string.billing_error_service_unavailable)

        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
            appContext.getString(R.string.billing_error_service_disconnected)

        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            appContext.getString(R.string.billing_error_unavailable)

        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            appContext.getString(R.string.billing_error_item_unavailable)

        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            appContext.getString(R.string.billing_error_developer)

        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
            appContext.getString(R.string.billing_error_already_owned)

        else -> result.debugMessage.ifBlank {
            appContext.getString(R.string.billing_error_generic)
        }
    }

    private fun Purchase.toView(): PurchaseView = PurchaseView(
        productId = products.firstOrNull().orEmpty(),
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        pending = purchaseState == Purchase.PurchaseState.PENDING,
        autoRenewing = isAutoRenewing,
        purchaseTimeMillis = purchaseTime,
        acknowledged = isAcknowledged,
        token = purchaseToken,
    )

    private fun ProductDetails.toPlan(): SubscriptionPlan? {
        val offers = subscriptionOfferDetails.orEmpty()
        val chosen = offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any {
                it.priceAmountMicros == 0L && it.billingPeriod.isNotBlank()
            }
        } ?: offers.firstOrNull() ?: return null

        val phases = chosen.pricingPhases.pricingPhaseList
        val trial = phases.firstOrNull {
            it.priceAmountMicros == 0L && it.billingPeriod.isNotBlank()
        }
        val recurring = phases.lastOrNull { it.priceAmountMicros > 0L } ?: phases.lastOrNull()

        return SubscriptionPlan(
            productId = productId,
            title = title,
            description = description,
            offerToken = chosen.offerToken,
            basePlanId = chosen.basePlanId,
            offerId = chosen.offerId,
            priceFormatted = recurring?.formattedPrice.orEmpty(),
            recurringPeriodIso = recurring?.billingPeriod,
            freeTrialPeriod = trial?.billingPeriod,
        )
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
