package com.glosso.app.billing

/**
 * Debug-only hook for [BillingProvider]. The fake lives in the debug source set,
 * so neither the class nor its development price is packaged into a release
 * artifact (APK or AAB). Point at Play in debug with `-PglossoBilling=real`.
 *
 * Convenience for day-to-day development: the debug build boots with Glosso Pro
 * already unlocked ([SubscriptionStatus.ACTIVE]), so every Pro surface is
 * reachable without walking the fake purchase flow first. This only changes the
 * wiring here; release never uses the fake. To test the paywall and the free/Pro
 * boundary again, either drop `initialStatus` (the [FakeSubscriptionRepository]
 * default is [SubscriptionStatus.NONE]), or call `reset()` from the debug test
 * hooks at runtime.
 */
internal fun debugFakeBilling(): SubscriptionRepository? =
    FakeSubscriptionRepository(initialStatus = SubscriptionStatus.ACTIVE)
