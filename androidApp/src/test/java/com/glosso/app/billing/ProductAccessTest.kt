package com.glosso.app.billing

import com.glosso.app.api.AccountState
import com.glosso.app.api.ProductConfig
import com.glosso.engine.model.LessonModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three plans and the open beta as the gate reads them. The product phase is
 * the server's, so a beta device opens everything and a plan the server grants
 * does too, while the default [ProductAccess.Unknown] leaves the old,
 * status-only behaviour untouched.
 */
class ProductAccessTest {

    private fun lesson(id: String, level: String = "A2") =
        LessonModel(id = id, level = level, theme = "city", title = id)

    @Test
    fun `the open beta unlocks every level and feature for any user`() {
        // Any user, whether account is null or explicitly plan=FREE and betaFounder=false
        val nonFounderFreeUser = AccountState(plan = "FREE", betaFounder = false)
        val access = AccessPolicy.productAccess(ProductConfig.BetaFallback, nonFounderFreeUser)
        val controller = AccessController(SubscriptionStatus.NONE, productAccess = access)

        assertTrue(access.betaFree)
        assertTrue(access.entitled)
        assertTrue(access.personalized)
        assertEquals(ProductPlan.PERSONALIZED, access.effectivePlan)
        assertTrue(controller.entitled)
        assertFalse("A2 is open in the beta", controller.isLocked(lesson("a2", level = "A2")))
        assertFalse("B1 is open in the beta", controller.isLocked(lesson("b1", level = "B1")))
        assertFalse("B2 is open in the beta", controller.isLocked(lesson("b2", level = "B2")))
        assertTrue("A2 offline download is open", controller.canDownload(lesson("a2", level = "A2")))
        assertTrue("B2 offline download is open", controller.canDownload(lesson("b2", level = "B2")))
        assertFalse("phonetic profile unlocked in beta", controller.isLocked(ProFeature.PHONETIC_PROFILE))
        assertFalse("personalized practice unlocked in beta", controller.isLocked(ProFeature.PERSONALIZED_PRACTICE))
        assertFalse(
            "requiresPaywall is false during beta",
            AccessPolicy.requiresPaywall(lesson("b2", level = "B2"), SubscriptionStatus.NONE, access = access),
        )
    }

    @Test
    fun `a server-granted plan opens the course without a local purchase`() {
        val paid = ProductConfig(mode = ProductConfig.MODE_FREE)
        val access = AccessPolicy.productAccess(paid, AccountState(plan = "PERSONALIZED"))
        val controller = AccessController(SubscriptionStatus.NONE, productAccess = access)

        assertTrue(access.entitled)
        assertTrue(access.personalized)
        assertEquals(ProductPlan.PERSONALIZED, access.effectivePlan)
        assertFalse(controller.isLocked(lesson("a2")))
        assertFalse(controller.isLocked(ProFeature.PERSONALIZED_PRACTICE))
    }

    @Test
    fun `a server-granted PRO plan opens PRO content but not personalized tier`() {
        val paid = ProductConfig(mode = ProductConfig.MODE_FREE)
        val access = AccessPolicy.productAccess(paid, AccountState(plan = "PRO"))
        val controller = AccessController(SubscriptionStatus.NONE, productAccess = access)

        assertTrue(access.entitled)
        assertFalse(access.personalized)
        assertEquals(ProductPlan.PRO, access.effectivePlan)
        assertFalse(controller.isLocked(lesson("a2")))
        assertFalse(controller.isLocked(ProFeature.PHONETIC_PROFILE))
        assertTrue(controller.isLocked(ProFeature.PERSONALIZED_PRACTICE))
    }

    /**
     * The server keeps a Beta Founder on plan FREE and marks `betaFounder` as an
     * orthogonal status. The gate must grant the PRO content tier from that status
     * alone as a lifetime entitlement grant, and must not hand out PERSONALIZED with it.
     */
    @Test
    fun `a Beta Founder reaches PRO content but not the personalized tier`() {
        val afterBeta = ProductConfig(mode = ProductConfig.MODE_FREE)
        val access = AccessPolicy.productAccess(afterBeta, AccountState(plan = "FREE", betaFounder = true))
        val controller = AccessController(SubscriptionStatus.NONE, productAccess = access)

        assertFalse("the open beta is over", access.betaFree)
        assertEquals("the server still reports FREE", ProductPlan.FREE, access.plan)
        assertTrue("the founder opens the PRO content", access.entitled)
        assertFalse("personalized tier is not unlocked by founder", access.personalized)
        assertEquals(ProductPlan.PRO, access.effectivePlan)
        assertEquals(EntitlementKind.LIFETIME, access.lifetimeGrant?.kind)
        assertEquals(ProductPlan.PRO, access.lifetimeGrant?.plan)

        assertFalse("A2 opens for a founder", controller.isLocked(lesson("a2")))
        assertFalse("the phonetic profile is PRO analytics", controller.isLocked(ProFeature.PHONETIC_PROFILE))
        assertTrue(
            "personalized practice is not included",
            controller.isLocked(ProFeature.PERSONALIZED_PRACTICE),
        )
    }

    @Test
    fun `a Beta Founder who also subscribes to PERSONALIZED reaches the top tier and keeps founder status`() {
        val afterBeta = ProductConfig(mode = ProductConfig.MODE_FREE)
        val access = AccessPolicy.productAccess(afterBeta, AccountState(plan = "PERSONALIZED", betaFounder = true))
        val controller = AccessController(SubscriptionStatus.NONE, productAccess = access)

        assertTrue(access.entitled)
        assertTrue(access.personalized)
        assertEquals(ProductPlan.PERSONALIZED, access.effectivePlan)
        assertTrue("founder status is preserved orthogonally", access.betaFounder)
        assertEquals(ProductPlan.PRO, access.lifetimeGrant?.plan)
        assertFalse(controller.isLocked(lesson("a2")))
        assertFalse(controller.isLocked(ProFeature.PERSONALIZED_PRACTICE))
    }

    @Test
    fun `a plain FREE learner without the founder status stays locked`() {
        val afterBeta = ProductConfig(mode = ProductConfig.MODE_FREE)
        val access = AccessPolicy.productAccess(afterBeta, AccountState(plan = "FREE"))
        val controller = AccessController(SubscriptionStatus.NONE, productAccess = access)

        assertFalse(access.betaFree)
        assertFalse(access.entitled)
        assertTrue(controller.isLocked(lesson("a2")))
    }

    @Test
    fun `without product access the old status-only frontier is unchanged`() {
        val controller = AccessController(SubscriptionStatus.NONE)
        assertFalse(controller.entitled)
        assertTrue("A2 still needs Pro outside the beta", controller.isLocked(lesson("a2")))
    }
}
