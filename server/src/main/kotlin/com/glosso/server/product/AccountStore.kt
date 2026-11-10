package com.glosso.server.product

import java.time.Instant
import java.util.UUID

/** A `glosso.users` row: the anonymous device identity. */
data class UserIdentity(
    val id: UUID,
    val deviceId: String,
    val createdAt: Instant,
)

/** The result of an identity upsert, with whether the user was just created. */
data class UserUpsert(
    val identity: UserIdentity,
    val created: Boolean,
)

/** The outcome of [AccountStore.signInWithExternalAccount]. */
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
 * Persistence for device identity and linked accounts. Services depend on this,
 * not on JDBC, so the decision logic is testable without a database.
 *
 * Moving an account to a new device (an external sign-in):
 * the account's `glosso.users` row adopts the new `device_id`. Whatever the new
 * device had already built on its own placeholder row is merged into the
 * account, never dropped: learner and phoneme profiles keep the most recently
 * updated row per key, open needs keep the most recent side per language, and
 * an external link is carried over when the account lacks one. Only then is
 * the emptied placeholder removed. The old device loses the account; on its
 * next call it simply starts a new anonymous one.
 */
interface AccountStore : AutoCloseable {

    /** Resolves a device id to its user, creating the user on first sight. */
    fun upsertUser(deviceId: String, now: Instant): UserUpsert

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
