package com.glosso.app.billing

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionResolverTest {

    private val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private val duringTrial = Instant.parse("2026-01-15T00:00:00Z").toEpochMilli()
    private val afterTrial = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()

    private fun view(
        purchased: Boolean = false,
        pending: Boolean = false,
        autoRenewing: Boolean = true,
        purchaseTimeMillis: Long = start,
        acknowledged: Boolean = true,
        token: String = "token",
    ) = PurchaseView(
        productId = "me.shirobyte42.glosso.pro",
        purchased = purchased,
        pending = pending,
        autoRenewing = autoRenewing,
        purchaseTimeMillis = purchaseTimeMillis,
        acknowledged = acknowledged,
        token = token,
    )

    private val trialPlan = SubscriptionPlan(
        productId = "me.shirobyte42.glosso.pro",
        title = "Glosso Pro",
        description = "",
        offerToken = "offer",
        basePlanId = "monthly",
        offerId = "trial",
        priceFormatted = "6,99 €",
        recurringPeriodIso = "P1M",
        freeTrialPeriod = "P1M",
    )

    @Test
    fun `a purchased purchase beats a pending one`() {
        val paid = view(purchased = true, acknowledged = false, token = "paid")
        val pending = view(pending = true, token = "pending")

        val selection = SubscriptionResolver.select(listOf(pending, paid))

        assertTrue(selection is PurchaseSelection.Paid)
        val resolved = selection as PurchaseSelection.Paid
        assertEquals("paid", resolved.purchase.token)
        // A purchased-but-unacknowledged purchase must reach the caller so it is
        // acknowledged, not hidden behind the pending one.
        assertFalse(resolved.purchase.acknowledged)
        assertEquals("pending", resolved.alsoPending?.token)
    }

    @Test
    fun `only a pending purchase is deferred`() {
        val selection = SubscriptionResolver.select(listOf(view(pending = true)))

        assertTrue(selection is PurchaseSelection.Deferred)
    }

    @Test
    fun `nothing relevant is none`() {
        assertEquals(PurchaseSelection.None, SubscriptionResolver.select(emptyList()))
    }

    @Test
    fun `a running trial is labelled trial when the offer phases are known`() {
        val paid = view(purchased = true)

        assertEquals(
            SubscriptionStatus.TRIAL,
            SubscriptionResolver.statusFor(paid, trialPlan, duringTrial),
        )
    }

    @Test
    fun `after the trial it is active`() {
        val paid = view(purchased = true)

        assertEquals(
            SubscriptionStatus.ACTIVE,
            SubscriptionResolver.statusFor(paid, trialPlan, afterTrial),
        )
    }

    @Test
    fun `an offer without trial is active from the start`() {
        val noTrial = trialPlan.copy(freeTrialPeriod = null)
        val paid = view(purchased = true)

        assertEquals(
            SubscriptionStatus.ACTIVE,
            SubscriptionResolver.statusFor(paid, noTrial, duringTrial),
        )
    }

    @Test
    fun `a canceled subscription keeps access until it is dropped`() {
        val paid = view(purchased = true, autoRenewing = false)

        assertEquals(
            SubscriptionStatus.CANCELED,
            SubscriptionResolver.statusFor(paid, trialPlan, afterTrial),
        )
    }

    @Test
    fun `without the offer phases the status stays unknown`() {
        // The race guard: do not call a trial ACTIVE just because the details have
        // not loaded yet.
        assertNull(SubscriptionResolver.statusFor(view(purchased = true), plan = null))
    }
}
