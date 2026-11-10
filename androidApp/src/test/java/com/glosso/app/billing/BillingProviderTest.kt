package com.glosso.app.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingProviderTest {

    @Test
    fun `release can never use the fake`() {
        // The gate that matters: even if the fake flag were mistakenly left on, a
        // non-debuggable build must fall through to Play.
        assertFalse(BillingProvider.useFake(billingFake = true, debuggable = false))
        assertFalse(BillingProvider.useFake(billingFake = false, debuggable = false))
    }

    @Test
    fun `debug uses the fake when the flag is set`() {
        assertTrue(BillingProvider.useFake(billingFake = true, debuggable = true))
    }

    @Test
    fun `debug can opt into the real client`() {
        assertFalse(BillingProvider.useFake(billingFake = false, debuggable = true))
    }
}
