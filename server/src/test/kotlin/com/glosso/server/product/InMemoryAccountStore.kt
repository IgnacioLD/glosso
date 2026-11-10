package com.glosso.server.product

import java.time.Instant
import java.util.UUID

/**
 * In-memory [AccountStore] for route tests: the real one is exercised only
 * against a live PostgreSQL, which the unit suite does not have.
 */
class InMemoryAccountStore : AccountStore {

    private val users = LinkedHashMap<String, UserIdentity>()
    private val links = LinkedHashMap<Pair<String, String>, UUID>()

    override fun upsertUser(deviceId: String, now: Instant): UserUpsert {
        users[deviceId]?.let { return UserUpsert(it, created = false) }
        val identity = UserIdentity(id = UUID.randomUUID(), deviceId = deviceId, createdAt = now)
        users[deviceId] = identity
        return UserUpsert(identity, created = true)
    }

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
        val moved = checkNotNull(moveAccount(linked, deviceId))
        return ExternalSignIn.SignedIn(moved, moved = true)
    }

    override fun linkedProviders(userId: UUID): List<String> =
        links.filterValues { it == userId }.keys.map { it.first }.sorted()

    /** Same merge rules as the JDBC store, for the state this fake keeps. */
    private fun moveAccount(accountUserId: UUID, deviceId: String): UserIdentity? {
        val entry = users.entries.firstOrNull { it.value.id == accountUserId } ?: return null
        val placeholder = users[deviceId]
        if (placeholder != null && placeholder.id != accountUserId) {
            links.replaceAll { key, userId ->
                val held = links.any { (other, owner) -> owner == accountUserId && other.first == key.first }
                if (userId == placeholder.id && !held) accountUserId else userId
            }
            links.values.removeIf { it == placeholder.id }
            users.remove(deviceId)
        }
        users.remove(entry.key)
        val moved = entry.value.copy(deviceId = deviceId)
        users[deviceId] = moved
        return moved
    }

    fun identityOf(deviceId: String): UserIdentity? = users[deviceId]
}
