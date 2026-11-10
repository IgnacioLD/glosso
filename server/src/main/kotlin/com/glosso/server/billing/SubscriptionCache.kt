package com.glosso.server.billing

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache of verified purchases, keyed by a hash of the purchase
 * token so raw tokens are never retained. Negative verdicts are cached too, so a
 * forged or expired token cannot be used to hammer the Play API. RTDN webhooks
 * invalidate an entry the moment Play reports a lifecycle change.
 *
 * A positive verdict is never cached past the subscription expiry, so an
 * entitlement cannot outlive the period it was paid for.
 */
class SubscriptionCache(
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val entitlement: Entitlement, val freshUntilMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(purchaseToken: String): Entitlement? {
        val key = key(purchaseToken)
        val entry = entries[key] ?: return null
        if (clock() >= entry.freshUntilMillis) {
            entries.remove(key, entry)
            return null
        }
        return entry.entitlement
    }

    fun put(purchaseToken: String, entitlement: Entitlement) {
        val now = clock()
        val ttl = if (entitlement.entitled && entitlement.expiresAtMillis != null) {
            minOf(ttlMillis, entitlement.expiresAtMillis - now)
        } else {
            ttlMillis
        }
        if (ttl <= 0) return
        entries[key(purchaseToken)] = Entry(entitlement, now + ttl)
    }

    fun invalidate(purchaseToken: String) {
        entries.remove(key(purchaseToken))
    }

    fun clear() = entries.clear()

    private fun key(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
