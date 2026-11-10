package com.glosso.server.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionCacheTest {

    private var now = 1_000_000L
    private val cache = SubscriptionCache(ttlMillis = 1_000, clock = { now })

    private val entitled = Entitlement(EntitlementStatus.ACTIVE, true, expiresAtMillis = 9_999_999_999_999L)
    private val expired = Entitlement(EntitlementStatus.NONE, false, expiresAtMillis = null)

    @Test
    fun `a stored verdict is returned before the ttl`() {
        cache.put("token", entitled)
        assertEquals(entitled, cache.get("token"))
    }

    @Test
    fun `a verdict is gone once the ttl passes`() {
        cache.put("token", entitled)
        now += 1_001
        assertNull(cache.get("token"))
    }

    @Test
    fun `invalidate drops the entry immediately`() {
        cache.put("token", entitled)
        cache.invalidate("token")
        assertNull(cache.get("token"))
    }

    @Test
    fun `different tokens do not collide`() {
        cache.put("a", entitled)
        cache.put("b", expired)
        assertEquals(entitled, cache.get("a"))
        assertEquals(expired, cache.get("b"))
    }

    @Test
    fun `an entitlement is not cached past its own expiry`() {
        val soon = Entitlement(EntitlementStatus.ACTIVE, true, expiresAtMillis = now + 100)
        cache.put("token", soon)
        assertEquals(soon, cache.get("token"))
        now += 101
        assertNull(cache.get("token"))
    }

    @Test
    fun `unentitled verdicts are cached too so forged tokens cannot hammer Play`() {
        cache.put("token", expired)
        assertEquals(expired, cache.get("token"))
    }

    @Test
    fun `a zero-length remaining entitlement is not cached`() {
        val alreadyGone = Entitlement(EntitlementStatus.ACTIVE, true, expiresAtMillis = now)
        cache.put("token", alreadyGone)
        assertNull(cache.get("token"))
    }
}
