package com.glosso.app.billing

/**
 * The three plans the product declares. There is no fourth: the top tier is
 * PERSONALIZED. The server names the plan in `GET /v1/me`; the config only
 * describes what each plan includes.
 */
enum class ProductPlan {
    FREE,
    PRO,
    PERSONALIZED,
    ;

    companion object {
        /** Maps the wire code to a plan. Anything unknown is FREE, never paid. */
        fun fromWire(code: String?): ProductPlan =
            entries.firstOrNull { it.name.equals(code?.trim(), ignoreCase = true) } ?: FREE
    }
}

/**
 * Kind of entitlement grant: recurring subscriptions vs permanent lifetime grants.
 */
enum class EntitlementKind {
    /** A recurring subscription (Play Billing, monthly/yearly with auto-renewal). */
    SUBSCRIPTION,

    /** A permanent lifetime grant (one-time purchase, Beta Founder, manual grant). Never expires, no renewal. */
    LIFETIME,
}

/**
 * A concrete entitlement held by this device.
 */
data class EntitlementGrant(
    val plan: ProductPlan,
    val kind: EntitlementKind = EntitlementKind.LIFETIME,
    val source: String = "beta_founder",
    val label: String? = null,
)

/**
 * The access the server grants this device, computed once from the product
 * config and the `/v1/me` answer (see `AccessPolicy.productAccess`). It is a
 * pure value the UI and the gate read, never a client-side switch:
 *
 * - [betaFree] is true while the product is in its open beta, which makes the
 *   whole course usable and sells nothing.
 * - [plan] is the server's plan for this device.
 * - [betaFounder] is the orthogonal founder status the server reports in
 *   `GET /v1/me`. It represents a permanent lifetime grant for the PRO tier.
 * - [lifetimeGrant] is any permanent, non-recurring grant (Beta Founder or lifetime purchase).
 * - [sellingEnabled] is true only when the product is out of beta, the plan's
 *   flag is on and a real price is published.
 *
 * [Unknown] is the conservative default for a caller that did not resolve the
 * config: no beta, FREE.
 */
data class ProductAccess(
    val betaFree: Boolean = false,
    val plan: ProductPlan = ProductPlan.FREE,
    val sellingEnabled: Boolean = false,
    val betaFounder: Boolean = false,
    val lifetimeGrant: EntitlementGrant? = if (betaFounder) EntitlementGrant(
        plan = ProductPlan.PRO,
        kind = EntitlementKind.LIFETIME,
        source = "beta_founder",
        label = "Beta Founder",
    ) else null,
) {
    /**
     * The plan the learner actually enjoys:
     * - During the open beta, everything is open (PERSONALIZED tier, all content, all features).
     * - Outside beta, a lifetime grant (like Beta Founder) gives permanent PRO content access for life
     *   without PERSONALIZED. If the user contracts PERSONALIZED, they reach that top tier.
     * - Regular users get the plan reported by the server.
     */
    val effectivePlan: ProductPlan
        get() = when {
            betaFree -> ProductPlan.PERSONALIZED
            plan == ProductPlan.PERSONALIZED -> ProductPlan.PERSONALIZED
            lifetimeGrant != null -> lifetimeGrant.plan
            else -> plan
        }

    /** True while the learner may use every content surface of the app. */
    val entitled: Boolean get() = betaFree || effectivePlan != ProductPlan.FREE

    /**
     * True when the learner reaches the personalization tier. The open beta
     * opens everything; outside beta only the PERSONALIZED plan does. A founder
     * lands on PRO, so this stays false for them unless they have PERSONALIZED.
     */
    val personalized: Boolean get() = betaFree || effectivePlan == ProductPlan.PERSONALIZED

    companion object {
        val Unknown = ProductAccess()
    }
}

/**
 * The client version policy read from `client` in the config. During the beta it
 * only ever warns: [belowMinimum] and [outdated] are meant for a soft notice, and
 * nothing in the app blocks on them. A version of 0 (the server did not say) is
 * ignored.
 */
data class VersionNotice(
    val currentVersion: Int,
    val recommendedVersion: Int,
    val minimumSupportedVersion: Int,
) {
    /** A newer build is recommended, but this one keeps working. */
    val outdated: Boolean get() = recommendedVersion > 0 && currentVersion < recommendedVersion

    /** Older than the minimum the server supports; still only a warning in beta. */
    val belowMinimum: Boolean get() = minimumSupportedVersion > 0 && currentVersion < minimumSupportedVersion

    /** True when there is anything worth telling the learner. */
    val visible: Boolean get() = outdated || belowMinimum

    companion object {
        val None = VersionNotice(0, 0, 0)
    }
}


