package com.glosso.server.product

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The server secret behind sign-in (`GLOSSO_ACCOUNT_SECRET`).
 *
 * It never leaves the server and is never stored in the database: each use gets
 * its own key derived from the secret, so a value computed for one purpose can
 * never be replayed as another.
 */
class AccountSecret(secret: ByteArray) {

    init {
        require(secret.size >= MIN_BYTES) { "$ENV must be at least $MIN_BYTES bytes" }
    }

    private val nonceKey = derive(secret, "glosso/sign-in-nonce/v1")

    /**
     * A sign-in nonce bound to [deviceId] and to [issuedAt]. The app passes it
     * to Google, which signs it into the ID token, so a token obtained for one
     * device cannot be replayed from another one, nor after [SIGN_IN_NONCE_TTL].
     * Stateless: the server keeps nothing between issuing and checking it.
     */
    fun signInNonce(deviceId: String, issuedAt: Instant): String {
        val seconds = issuedAt.epochSecond.toString()
        return "$seconds.${nonceMac(deviceId, seconds)}"
    }

    /** True when [nonce] was issued by [signInNonce] for [deviceId] and is still fresh. */
    fun isValidSignInNonce(nonce: String, deviceId: String, now: Instant): Boolean {
        val seconds = nonce.substringBefore('.', missingDelimiterValue = "")
        // The seconds come from an attacker-controlled claim; a signed nonce is
        // ours, but a malformed one must answer "invalid", never throw.
        val issuedAt = runCatching { Instant.ofEpochSecond(seconds.toLong()) }.getOrNull() ?: return false
        val expected = nonceMac(deviceId, seconds)
        if (!MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                nonce.substringAfter('.').toByteArray(Charsets.US_ASCII),
            )
        ) {
            return false
        }
        val age = Duration.between(issuedAt, now)
        if (age.isNegative && age.abs() > CLOCK_SKEW) return false
        if (age > SIGN_IN_NONCE_TTL) return false
        return true
    }

    private fun nonceMac(deviceId: String, seconds: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(nonceKey, "$seconds\n$deviceId"))

    companion object {
        const val ENV = "GLOSSO_ACCOUNT_SECRET"

        /** 256 bits: the HMAC key size, and what `openssl rand -base64 32` gives. */
        const val MIN_BYTES = 32

        /** Enough to pick an account in the Google sheet, short enough to limit replay. */
        val SIGN_IN_NONCE_TTL: Duration = Duration.ofMinutes(10)
        private val CLOCK_SKEW: Duration = Duration.ofMinutes(1)

        /**
         * The secret from the environment, or null when unset (sign-in is then
         * disabled). A secret that is set but too short is a misconfiguration
         * and fails the start instead of silently weakening it.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): AccountSecret? {
            val raw = env[ENV]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return AccountSecret(raw.toByteArray(Charsets.UTF_8))
        }

        private fun derive(secret: ByteArray, purpose: String): ByteArray =
            hmac(secret, purpose)

        private fun hmac(key: ByteArray, message: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(message.toByteArray(Charsets.UTF_8))
            }
    }
}
