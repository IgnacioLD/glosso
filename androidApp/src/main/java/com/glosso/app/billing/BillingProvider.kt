package com.glosso.app.billing

import android.content.Context
import com.glosso.app.BuildConfig

/**
 * Builds the one [SubscriptionRepository] for the process. Debug builds use the
 * fake by default so the whole paywall and gate flow can be walked without Play;
 * a license-tester build can opt into the real client with
 * `-PglossoBilling=real`. Release builds always use Play.
 *
 * The fake itself lives in the debug source set (`debugFakeBilling`), so no fake
 * code or development price is ever compiled into a release APK.
 */
object BillingProvider {

    @Volatile
    private var instance: SubscriptionRepository? = null

    fun get(context: Context): SubscriptionRepository =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): SubscriptionRepository =
        if (useFake(BuildConfig.BILLING_FAKE, BuildConfig.DEBUG)) {
            debugFakeBilling() ?: PlaySubscriptionRepository(context)
        } else {
            PlaySubscriptionRepository(context)
        }

    /**
     * The only condition that hands out the fake. It requires a debuggable build
     * as well as the fake flag, so a production (release) build can never use the
     * fake subscription even if `BILLING_FAKE` were misconfigured: [BuildConfig.DEBUG]
     * is a compile-time `false` in release. Pure, so a unit test pins it.
     */
    fun useFake(billingFake: Boolean, debuggable: Boolean): Boolean =
        billingFake && debuggable

    /** Test seam: install a specific repository, or null to rebuild on next use. */
    fun override(repository: SubscriptionRepository?) {
        synchronized(this) { instance = repository }
    }
}
