package com.glosso.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPlanTest {

    private fun plan(trialPeriod: String? = "P1M") = SubscriptionPlan(
        productId = "me.shirobyte42.glosso.pro",
        title = "Glosso Pro",
        description = "Curso completo",
        offerToken = "token",
        basePlanId = "monthly",
        offerId = "trial",
        priceFormatted = "6,99 €",
        recurringPeriodIso = "P1M",
        freeTrialPeriod = trialPeriod,
    )

    @Test
    fun `hasFreeTrial reflects the trial period`() {
        assertTrue(plan(trialPeriod = "P1M").hasFreeTrial)
        assertFalse(plan(trialPeriod = null).hasFreeTrial)
    }

    @Test
    fun `trial end comes from the trial period`() {
        assertEquals(
            BillingPeriods.endMillis(0L, "P1M"),
            plan().trialEndMillis(0L),
        )
    }
}
