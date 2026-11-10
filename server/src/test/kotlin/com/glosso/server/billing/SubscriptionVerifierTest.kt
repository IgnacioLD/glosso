package com.glosso.server.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionVerifierTest {

    private val product = "com.glosso.app.pro"
    private val now = 1_000_000_000_000L

    private fun purchase(
        state: String,
        expiry: Long? = null,
        offerId: String? = null,
        productId: String = product,
    ) = PlaySubscription(
        state = state,
        lineItems = listOf(
            PlayLineItem(productId = productId, expiryTimeMillis = expiry, autoRenewing = true, offerId = offerId),
        ),
        cancelTimeMillis = null,
        acknowledgeState = "ACKNOWLEDGED",
    )

    private fun verify(purchase: PlaySubscription?, trialOfferId: String? = null) =
        SubscriptionVerifier.verify(purchase, product, trialOfferId, now)

    @Test
    fun `an unknown token is not entitled`() {
        val entitlement = verify(null)
        assertEquals(EntitlementStatus.NONE, entitlement.status)
        assertFalse(entitlement.entitled)
    }

    @Test
    fun `active subscription is entitled and carries the expiry`() {
        val entitlement = verify(purchase("SUBSCRIPTION_STATE_ACTIVE", expiry = now + 5_000))
        assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
        assertTrue(entitlement.entitled)
        assertEquals(now + 5_000, entitlement.expiresAtMillis)
    }

    @Test
    fun `active subscription on the configured trial offer is a trial`() {
        val entitlement = verify(
            purchase("SUBSCRIPTION_STATE_ACTIVE", expiry = now + 5_000, offerId = "free-month"),
            trialOfferId = "free-month",
        )
        assertEquals(EntitlementStatus.TRIAL, entitlement.status)
        assertTrue(entitlement.entitled)
    }

    @Test
    fun `a different offer is not reported as trial`() {
        val entitlement = verify(
            purchase("SUBSCRIPTION_STATE_ACTIVE", expiry = now + 5_000, offerId = "other"),
            trialOfferId = "free-month",
        )
        assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
    }

    @Test
    fun `grace period keeps entitlement`() {
        val entitlement = verify(purchase("SUBSCRIPTION_STATE_IN_GRACE_PERIOD", expiry = now + 5_000))
        assertEquals(EntitlementStatus.GRACE, entitlement.status)
        assertTrue(entitlement.entitled)
    }

    @Test
    fun `canceled but not expired keeps entitlement until expiry`() {
        val entitlement = verify(purchase("SUBSCRIPTION_STATE_CANCELED", expiry = now + 5_000))
        assertEquals(EntitlementStatus.CANCELED, entitlement.status)
        assertTrue(entitlement.entitled)
    }

    @Test
    fun `canceled and past expiry is not entitled`() {
        val entitlement = verify(purchase("SUBSCRIPTION_STATE_CANCELED", expiry = now - 1))
        assertEquals(EntitlementStatus.CANCELED, entitlement.status)
        assertFalse(entitlement.entitled)
    }

    @Test
    fun `expired is not entitled`() {
        val entitlement = verify(purchase("SUBSCRIPTION_STATE_EXPIRED", expiry = now - 1))
        assertEquals(EntitlementStatus.NONE, entitlement.status)
        assertFalse(entitlement.entitled)
    }

    @Test
    fun `on hold pending and paused grant nothing`() {
        for (state in listOf(
            "SUBSCRIPTION_STATE_ON_HOLD",
            "SUBSCRIPTION_STATE_PENDING",
            "SUBSCRIPTION_STATE_PAUSED",
        )) {
            val entitlement = verify(purchase(state))
            assertEquals(EntitlementStatus.PENDING, entitlement.status, state)
            assertFalse(entitlement.entitled, state)
        }
    }

    @Test
    fun `an unknown Play state is not entitled`() {
        val entitlement = verify(purchase("SUBSCRIPTION_STATE_SOMETHING_NEW"))
        assertEquals(EntitlementStatus.NONE, entitlement.status)
        assertFalse(entitlement.entitled)
    }

    @Test
    fun `a purchase for another product is not entitled`() {
        val entitlement = verify(purchase("SUBSCRIPTION_STATE_ACTIVE", expiry = now + 1, productId = "other.product"))
        assertEquals(EntitlementStatus.NONE, entitlement.status)
        assertFalse(entitlement.entitled)
    }
}
