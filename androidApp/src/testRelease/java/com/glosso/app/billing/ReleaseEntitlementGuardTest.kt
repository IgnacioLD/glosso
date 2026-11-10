package com.glosso.app.billing

import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs only for the release variant (`:androidApp:testReleaseUnitTest`). Pins the
 * two facts that make the debug Pro unlock impossible in release:
 *
 *  1. the only entitlement seam the release binary has ([debugFakeBilling])
 *     returns no repository at all, so there is no fake to select;
 *  2. `FakeSubscriptionRepository` is not even on the release classpath, so a
 *     release build has no class that could set `SubscriptionStatus.ACTIVE`.
 *
 * `:androidApp:verifyReleaseApkEntitlement` and
 * `:androidApp:verifyReleaseBundleEntitlement` check the packaged APK and AAB for
 * the same absence at the artifact level.
 */
class ReleaseEntitlementGuardTest {

    @Test
    fun `the release fake seam produces no repository`() {
        assertNull(debugFakeBilling())
    }

    @Test
    fun `the fake repository is not on the release classpath`() {
        try {
            Class.forName("com.glosso.app.billing.FakeSubscriptionRepository")
            fail("FakeSubscriptionRepository must not be compiled into release")
        } catch (expected: ClassNotFoundException) {
            // expected: the debug source set is not part of the release build
        }
    }
}
