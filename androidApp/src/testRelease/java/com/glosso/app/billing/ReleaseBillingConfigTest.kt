package com.glosso.app.billing

import com.glosso.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Runs only for the release variant (`:androidApp:testReleaseUnitTest`), so it
 * reads the real release [BuildConfig] the shipped APK is built with.
 */
class ReleaseBillingConfigTest {

    @Test
    fun `release is not debuggable and never ships the fake`() {
        assertFalse(BuildConfig.DEBUG)
        assertFalse(BuildConfig.BILLING_FAKE)
        assertFalse(BillingProvider.useFake(BuildConfig.BILLING_FAKE, BuildConfig.DEBUG))
    }
}
