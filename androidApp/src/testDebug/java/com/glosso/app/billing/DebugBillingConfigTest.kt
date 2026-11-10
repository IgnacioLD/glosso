package com.glosso.app.billing

import com.glosso.app.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs only for the debug variant (`:androidApp:testDebugUnitTest`), so it pins
 * the default debug contract: debuggable, and the fake unless a license-tester
 * build explicitly asked for the real client with `-PglossoBilling=real`. That
 * override is a different build and intentionally not covered here.
 */
class DebugBillingConfigTest {

    @Test
    fun `debug ships the fake by default`() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.BILLING_FAKE)
        assertTrue(BillingProvider.useFake(BuildConfig.BILLING_FAKE, BuildConfig.DEBUG))
    }
}
