package com.glosso.app

import androidx.lifecycle.ViewModel
import com.glosso.app.billing.SubscriptionPlan
import com.glosso.app.billing.SubscriptionRepository
import com.glosso.app.billing.SubscriptionStatus
import com.glosso.app.platform.PlatformActivity
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI's view of the subscription. It only forwards to the repository, so the
 * screens depend on state, not on Google Play.
 */
class SubscriptionViewModel(private val repository: SubscriptionRepository) : ViewModel() {

    val status: StateFlow<SubscriptionStatus> = repository.status
    val plan: StateFlow<SubscriptionPlan?> = repository.plan
    val loading: StateFlow<Boolean> = repository.loading
    val error: StateFlow<String?> = repository.error

    init {
        // Connect, load the offer and restore on first use. Idempotent.
        repository.start()
    }

    fun refresh() = repository.refresh(restore = true)

    fun subscribe(activity: PlatformActivity) = repository.subscribe(activity)

    fun clearError() = repository.clearError()
}
