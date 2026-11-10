package com.glosso.app.billing

import com.glosso.app.platform.PlatformActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * No store purchases on iOS during the beta (decision 4 of
 * `docs/IOS-KMP-PLAN.md`): access comes from `/v1/config` and `/v1/me` alone and
 * the app shows no prices. TODO(StoreKit 2): once the product moves to FREE with
 * paid plans.
 */
internal object NoSubscriptions : SubscriptionRepository {
    override val status: StateFlow<SubscriptionStatus> = MutableStateFlow(SubscriptionStatus.NONE)
    override val plan: StateFlow<SubscriptionPlan?> = MutableStateFlow(null)
    override val loading: StateFlow<Boolean> = MutableStateFlow(false)
    override val error: StateFlow<String?> = MutableStateFlow(null)

    override fun start() = Unit
    override fun refresh(restore: Boolean) = Unit
    override fun subscribe(activity: PlatformActivity) = Unit
    override fun clearError() = Unit
}
