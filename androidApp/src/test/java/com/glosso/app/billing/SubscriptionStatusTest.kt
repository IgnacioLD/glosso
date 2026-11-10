package com.glosso.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI must render exactly the states Play and the fake can produce, without
 * inventing an "expired" or "restored" state: an expired subscription is a
 * dropped purchase ([NONE]) and a restore is a re-query, not a state.
 */
class SubscriptionStatusTest {

    @Test
    fun `the six real states are the only ones`() {
        assertEquals(
            listOf("NONE", "TRIAL", "ACTIVE", "GRACE", "CANCELED", "PENDING"),
            SubscriptionStatus.entries.map { it.name },
        )
        assertFalse(SubscriptionStatus.entries.any { it.name.contains("EXPIRED") })
    }

    @Test
    fun `expired is no purchase and grants nothing`() {
        assertFalse(SubscriptionStatus.NONE.isEntitled)
    }

    @Test
    fun `entitlement follows the documented rule`() {
        val entitled = listOf(
            SubscriptionStatus.TRIAL,
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.GRACE,
            SubscriptionStatus.CANCELED,
        )
        entitled.forEach { assertTrue("$it should be entitled", it.isEntitled) }
        listOf(SubscriptionStatus.NONE, SubscriptionStatus.PENDING)
            .forEach { assertFalse("$it should not be entitled", it.isEntitled) }
    }
}
