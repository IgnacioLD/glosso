package com.glosso.server.product

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server secret: a sign-in nonce is bound to its device and to a short
 * window, and a secret that is too short is refused instead of weakening it.
 */
class AccountSecretTest {

    private val now: Instant = Instant.parse("2026-09-26T12:00:00Z")
    private val secret = AccountSecret(ByteArray(32) { 1 })

    @Test
    fun `a short or missing secret is rejected instead of weakening the hash`() {
        assertFailsWith<IllegalArgumentException> { AccountSecret(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { AccountSecret.fromEnv(mapOf(AccountSecret.ENV to "too-short")) }
        assertNull(AccountSecret.fromEnv(emptyMap()))
        assertNull(AccountSecret.fromEnv(mapOf(AccountSecret.ENV to "  ")))
    }

    @Test
    fun `a nonce is valid only for its device and while fresh`() {
        val nonce = secret.signInNonce("device-a", now)

        assertTrue(secret.isValidSignInNonce(nonce, "device-a", now.plusSeconds(60)))
        assertFalse(secret.isValidSignInNonce(nonce, "device-b", now.plusSeconds(60)))
        assertFalse(
            secret.isValidSignInNonce(nonce, "device-a", now.plus(AccountSecret.SIGN_IN_NONCE_TTL).plusSeconds(1)),
        )
        assertFalse(secret.isValidSignInNonce(nonce, "device-a", now.minus(Duration.ofMinutes(5))))
        assertFalse(AccountSecret(ByteArray(32) { 9 }).isValidSignInNonce(nonce, "device-a", now))
    }

    @Test
    fun `a forged or malformed nonce is rejected`() {
        val nonce = secret.signInNonce("device-a", now)
        val later = "${now.epochSecond + 30}.${nonce.substringAfter('.')}"

        assertFalse(secret.isValidSignInNonce(later, "device-a", now.plusSeconds(60)))
        for (bad in listOf("", ".", "abc", "123", "123.", "x.${nonce.substringAfter('.')}")) {
            assertFalse(secret.isValidSignInNonce(bad, "device-a", now), bad)
        }
    }

    @Test
    fun `a nonce with an out-of-range timestamp is invalid, not a crash`() {
        // Instant.ofEpochSecond throws DateTimeException beyond its range; the
        // check must answer false (401), never surface a 500.
        for (bad in listOf("${Long.MAX_VALUE}.mac", "9223372036854775808.mac")) {
            assertFalse(secret.isValidSignInNonce(bad, "device-a", now), bad)
        }
    }
}
