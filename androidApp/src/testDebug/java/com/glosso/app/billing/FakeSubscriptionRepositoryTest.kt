package com.glosso.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeSubscriptionRepositoryTest {

    private val repo = FakeSubscriptionRepository()

    @Test
    fun `starts with no subscription`() {
        assertEquals(SubscriptionStatus.NONE, repo.status.value)
        assertEquals(FakeSubscriptionRepository.devPlan, repo.plan.value)
    }

    @Test
    fun `subscribing starts the trial`() {
        repo.simulateSubscribe()
        assertEquals(SubscriptionStatus.TRIAL, repo.status.value)
    }

    @Test
    fun `trial can become active or canceled`() {
        repo.simulateActivate()
        assertEquals(SubscriptionStatus.ACTIVE, repo.status.value)

        repo.simulateCancel()
        assertEquals(SubscriptionStatus.CANCELED, repo.status.value)
    }

    @Test
    fun `grace and pending are reported as states`() {
        repo.simulateGrace()
        assertEquals(SubscriptionStatus.GRACE, repo.status.value)

        repo.simulatePending()
        assertEquals(SubscriptionStatus.PENDING, repo.status.value)
    }

    @Test
    fun `errors can be raised and cleared`() {
        repo.simulateError("sin red")
        assertEquals("sin red", repo.error.value)

        repo.clearError()
        assertNull(repo.error.value)
    }

    @Test
    fun `restoring clears the last error`() {
        repo.simulateError("sin red")

        repo.refresh(restore = true)

        assertNull(repo.error.value)
    }

    @Test
    fun `an offer without a trial activates immediately`() {
        val noTrial = FakeSubscriptionRepository.devPlan.copy(freeTrialPeriod = null)
        val paid = FakeSubscriptionRepository(planValue = noTrial)

        paid.simulateSubscribe()

        assertEquals(SubscriptionStatus.ACTIVE, paid.status.value)
    }

    @Test
    fun `subscribing twice never demotes an existing subscription`() {
        repo.simulateActivate()

        repo.simulateSubscribe()

        assertEquals(SubscriptionStatus.ACTIVE, repo.status.value)
    }

    @Test
    fun `reset returns to a fresh install`() {
        repo.simulateActivate()
        repo.reset()
        assertEquals(SubscriptionStatus.NONE, repo.status.value)
        assertNull(repo.error.value)
    }
}
