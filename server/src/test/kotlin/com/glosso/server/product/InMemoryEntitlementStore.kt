package com.glosso.server.product

import java.time.Instant
import java.util.UUID

/**
 * In-memory [EntitlementStore] for route tests: the real one is exercised only
 * against a live PostgreSQL, which the unit suite does not have.
 */
class InMemoryEntitlementStore : EntitlementStore {

    private val users = LinkedHashMap<String, UserIdentity>()
    private val entitlements = LinkedHashMap<UUID, StoredEntitlement>()
    private val grants = mutableListOf<LifetimeGrant>()
    private val links = LinkedHashMap<Pair<String, String>, UUID>()

    override fun upsertUser(deviceId: String, now: Instant): UserUpsert {
        users[deviceId]?.let { return UserUpsert(it, created = false) }
        val identity = UserIdentity(
            id = UUID.randomUUID(),
            deviceId = deviceId,
            createdAt = now,
            betaFounder = false,
            betaFounderSince = null,
            betaFounderUntil = null,
            betaFounderBenefit = null,
        )
        users[deviceId] = identity
        return UserUpsert(identity, created = true)
    }

    override fun markBetaFounder(userId: UUID, since: Instant, until: Instant?, benefit: String?) {
        val entry = users.entries.firstOrNull { it.value.id == userId } ?: return
        val current = entry.value
        if (current.betaFounder) return
        users[entry.key] = current.copy(
            betaFounder = true,
            betaFounderSince = since,
            betaFounderUntil = until,
            betaFounderBenefit = benefit,
        )
    }

    override fun entitlement(userId: UUID): StoredEntitlement? = entitlements[userId]

    override fun updateEntitlementsByProviderTokenHash(
        providerTokenHash: String,
        entitled: Boolean,
        status: String,
        expiresAt: Instant?,
        autoRenew: Boolean,
    ) {
        entitlements.values
            .filter { it.providerTokenHash == providerTokenHash && it.plan != "PERSONALIZED" }
            .forEach { existing ->
                entitlements[existing.userId] = existing.copy(
                    plan = if (entitled) existing.providerPlan() else "FREE",
                    status = status,
                    expiresAt = expiresAt,
                    autoRenew = autoRenew,
                    provider = existing.provider ?: "google_play",
                )
            }
    }

    override fun upsertEntitlement(entitlement: StoredEntitlement) {
        entitlements[entitlement.userId] = entitlement
    }

    override fun lifetimeGrants(userId: UUID): List<LifetimeGrant> = grants.filter { it.userId == userId }

    override fun grantLifetime(
        userId: UUID,
        plan: String,
        source: String,
        grantedAt: Instant,
        provider: String?,
        providerRefHash: String?,
    ) {
        if (grants.any { it.userId == userId && it.plan == plan && it.source == source }) return
        grants += LifetimeGrant(userId, plan, source, provider, grantedAt)
    }

    fun grantsOf(deviceId: String): List<LifetimeGrant> =
        users[deviceId]?.let { lifetimeGrants(it.id) } ?: emptyList()

    override fun signInWithExternalAccount(
        provider: String,
        subject: String,
        deviceId: String,
        now: Instant,
    ): ExternalSignIn {
        val deviceUser = users[deviceId] ?: throw AccountChangedConcurrently("no user for the signing-in device")
        val linked = links[provider to subject]
        if (linked == deviceUser.id) return ExternalSignIn.SignedIn(deviceUser, moved = false)
        if (links.any { (key, userId) -> key.first == provider && userId == deviceUser.id }) {
            return ExternalSignIn.Conflict
        }
        if (linked == null) {
            links[provider to subject] = deviceUser.id
            return ExternalSignIn.SignedIn(deviceUser, moved = false)
        }
        val moved = checkNotNull(moveAccount(linked, deviceId, now))
        return ExternalSignIn.SignedIn(moved, moved = true)
    }

    override fun linkedProviders(userId: UUID): List<String> =
        links.filterValues { it == userId }.keys.map { it.first }.sorted()

    /** Same merge rules as the JDBC store, for the state this fake keeps. */
    private fun moveAccount(accountUserId: UUID, deviceId: String, now: Instant): UserIdentity? {
        val entry = users.entries.firstOrNull { it.value.id == accountUserId } ?: return null
        var account = entry.value
        val placeholder = users[deviceId]
        if (placeholder != null && placeholder.id != accountUserId) {
            grants.replaceAll { grant ->
                val held = grants.any { it.userId == accountUserId && it.plan == grant.plan && it.source == grant.source }
                if (grant.userId == placeholder.id && !held) grant.copy(userId = accountUserId) else grant
            }
            grants.removeIf { it.userId == placeholder.id }
            val placeholderEntitlement = entitlements.remove(placeholder.id)
            val accountEntitlement = entitlements[accountUserId]
            if (placeholderEntitlement != null &&
                (accountEntitlement == null || entitlementMergeOrder(now).compare(accountEntitlement, placeholderEntitlement) < 0)
            ) {
                entitlements[accountUserId] = placeholderEntitlement.copy(userId = accountUserId)
            }
            links.replaceAll { key, userId ->
                val held = links.any { (other, owner) -> owner == accountUserId && other.first == key.first }
                if (userId == placeholder.id && !held) accountUserId else userId
            }
            links.values.removeIf { it == placeholder.id }
            if (!account.betaFounder && placeholder.betaFounder) {
                account = account.copy(
                    betaFounder = true,
                    betaFounderSince = placeholder.betaFounderSince,
                    betaFounderUntil = placeholder.betaFounderUntil,
                    betaFounderBenefit = placeholder.betaFounderBenefit,
                )
            }
            users.remove(deviceId)
        }
        users.remove(entry.key)
        val moved = account.copy(deviceId = deviceId)
        users[deviceId] = moved
        return moved
    }

    fun identityOf(deviceId: String): UserIdentity? = users[deviceId]

    fun entitlementOf(deviceId: String): StoredEntitlement? =
        users[deviceId]?.let { entitlements[it.id] }
}
