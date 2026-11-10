package com.glosso.server.product

import com.glosso.server.billing.EntitlementStatus
import java.time.Instant
import java.util.UUID

/**
 * A `glosso.users` row: the anonymous device identity plus the permanent Beta
 * Founder status. Once set, the founder status is never cleared or recomputed.
 */
data class UserIdentity(
    val id: UUID,
    val deviceId: String,
    val createdAt: Instant,
    val betaFounder: Boolean,
    val betaFounderSince: Instant?,
    val betaFounderUntil: Instant?,
    val betaFounderBenefit: String?,
)

/** The result of an identity upsert, with whether the user was just created. */
data class UserUpsert(
    val identity: UserIdentity,
    val created: Boolean,
)

/**
 * One effective entitlement, as persisted in `glosso.entitlements`. `plan` and
 * `status` are the raw stored values; the effective plan is resolved by
 * [EntitlementService].
 */
data class StoredEntitlement(
    val userId: UUID,
    val plan: String,
    val status: String,
    val source: String,
    val expiresAt: Instant?,
    val startedAt: Instant,
    val autoRenew: Boolean,
    val provider: String?,
    val providerTokenHash: String?,
)

/**
 * A one-time entitlement from `glosso.lifetime_grants`: a plan granted for life,
 * with no expiry, renewal or grace period. The Beta Founder PRO is one
 * (`source = beta_founder`); a future one-time purchase would be another
 * (`source = purchase`). Only non-revoked grants are ever loaded.
 */
data class LifetimeGrant(
    val userId: UUID,
    val plan: String,
    val source: String,
    val provider: String?,
    val grantedAt: Instant,
)

/** The outcome of [EntitlementStore.signInWithExternalAccount]. */
sealed interface ExternalSignIn {
    /** Signed in; [moved] is true when the account came from another device. */
    data class SignedIn(val identity: UserIdentity, val moved: Boolean) : ExternalSignIn

    /** The device's account is already linked to a different identity of the provider. */
    data object Conflict : ExternalSignIn
}

/**
 * A user row an account flow needed vanished or moved under it: another
 * request merged it or moved its device between the read and the lock. Nothing
 * was written; the client retries and sees the new state (409 on the API).
 */
class AccountChangedConcurrently(message: String) : RuntimeException(message)

/**
 * Play sells one paid tier, PRO; a row already on a higher plan (PERSONALIZED)
 * is never downgraded when Play reports it as entitled.
 */
fun StoredEntitlement.providerPlan(): String =
    if (plan == "PRO" || plan == "PERSONALIZED") plan else "PRO"

/** The plans that are paid for, as opposed to FREE. */
val PAID_PLANS: Set<String> = setOf("PRO", "PERSONALIZED")

/** The order of plans, for picking the highest one. */
fun planRank(plan: String?): Int = when (plan) {
    "PERSONALIZED" -> 2
    "PRO" -> 1
    else -> 0
}

/**
 * A stored verdict only grants a paid plan while its status entitles and, when
 * an expiry is known, that expiry is still in the future. This demotes both an
 * ACTIVE row whose period lapsed and a CANCELED one past its paid-through
 * date, so an entitlement that Play has not refreshed yet cannot over-grant.
 */
fun StoredEntitlement.isInForce(now: Instant): Boolean {
    val status = runCatching { EntitlementStatus.valueOf(status) }.getOrNull() ?: return false
    if (!status.isEntitled) return false
    return expiresAt == null || expiresAt.isAfter(now)
}

/**
 * Orders two entitlement rows when an account move merges two users and only
 * one row can stay: a paid plan in force beats anything that is not, then the
 * higher plan, then the one that runs longer (no expiry is longest).
 */
fun entitlementMergeOrder(now: Instant): Comparator<StoredEntitlement> = compareBy(
    { it.isInForce(now) && it.plan in PAID_PLANS },
    { planRank(it.plan) },
    { it.expiresAt ?: Instant.MAX },
)

/**
 * Persistence for identity and entitlements. Services depend on this, not on
 * JDBC, so the decision logic is testable without a database.
 *
 * Moving an account to a new device (an external sign-in):
 * the account's `glosso.users` row adopts the new `device_id`, so the new device
 * inherits the Beta Founder status and the lifetime grants. Whatever the new
 * device had already built on its own placeholder row is merged into the
 * account, never dropped: learner and phoneme profiles keep the most recently
 * updated row per key, open needs keep the most recent side per language,
 * lifetime grants and an external link are carried over when the account lacks
 * them, the Beta Founder status is the union of both, and of the two
 * entitlement rows (there is one per user) the highest by
 * [entitlementMergeOrder] is kept; the other one is dropped with the
 * placeholder. Only then is the emptied placeholder
 * removed. The old device loses the account; on its next call it simply starts
 * a new anonymous one.
 */
interface EntitlementStore : AutoCloseable {

    /** Resolves a device id to its user, creating the user on first sight. */
    fun upsertUser(deviceId: String, now: Instant): UserUpsert

    /** Marks a user as a founder once; a no-op when already marked. */
    fun markBetaFounder(userId: UUID, since: Instant, until: Instant?, benefit: String?)

    fun entitlement(userId: UUID): StoredEntitlement?

    /**
     * Applies an RTDN verdict to every entitlement verified with [providerTokenHash].
     * A purchase token is not unique: the same purchase can have been verified
     * from several devices, so a refund or renewal has to reach all of them, not
     * just the first row a lookup returns. The `provider_token_hash` is preserved
     * so later notifications for the same token still find the rows. A
     * `PERSONALIZED` row is a grant made outside Play and is left untouched: a
     * Play notification cannot revoke it. [autoRenew] carries a cancellation
     * (Play stops auto-renewing while the paid period still runs).
     */
    fun updateEntitlementsByProviderTokenHash(
        providerTokenHash: String,
        entitled: Boolean,
        status: String,
        expiresAt: Instant?,
        autoRenew: Boolean,
    )

    fun upsertEntitlement(entitlement: StoredEntitlement)

    /** The user's non-revoked lifetime grants. */
    fun lifetimeGrants(userId: UUID): List<LifetimeGrant>

    /**
     * Records a lifetime grant once per (user, plan, source); a repeat is a
     * no-op, and a grant revoked for fraud is never silently restored.
     */
    fun grantLifetime(
        userId: UUID,
        plan: String,
        source: String,
        grantedAt: Instant,
        provider: String? = null,
        providerRefHash: String? = null,
    )

    /**
     * Signs [deviceId] in with the external identity ([provider], [subject]),
     * for example the `sub` of a verified Google ID token:
     * - not linked yet: it is linked to the device's current user, which
     *   becomes a stable account;
     * - linked to the device's user: nothing changes;
     * - linked to another user: that account moves to this device, merging
     *   what this device had (see the account move above).
     * The device user must already exist ([upsertUser]). Returns
     * [ExternalSignIn.Conflict] when the device's user is already linked to a
     * different identity of the same provider: one person, one account.
     * Throws [AccountChangedConcurrently] when the device's user or the link
     * changed under a concurrent flow.
     */
    fun signInWithExternalAccount(provider: String, subject: String, deviceId: String, now: Instant): ExternalSignIn

    /** The providers [userId] is linked to, sorted; never the subjects. */
    fun linkedProviders(userId: UUID): List<String>

    override fun close() {}
}
