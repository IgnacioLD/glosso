package com.glosso.server.product

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Google ID token checks, against a local key standing in for Google's
 * published ones: signature, audience, issuer and expiry must all hold.
 */
class NimbusGoogleIdTokenVerifierTest {

    private val google: RSAKey = GoogleTokens.key("google")
    private val verifier = NimbusGoogleIdTokenVerifier(setOf(GoogleTokens.CLIENT_ID), GoogleTokens.keys(google))

    @Test
    fun `a valid token yields only the subject and the nonce`() {
        val identity = verifier.verify(GoogleTokens.sign(google, subject = "1234", nonce = "n-1"))

        assertEquals(GoogleIdentity("1234", "n-1"), identity)
    }

    @Test
    fun `a token for another client is refused`() {
        assertNull(verifier.verify(GoogleTokens.sign(google, audience = "someone-else.apps.googleusercontent.com")))
    }

    @Test
    fun `a token from another issuer is refused`() {
        assertNull(verifier.verify(GoogleTokens.sign(google, issuer = "https://evil.example.com")))
    }

    @Test
    fun `an expired token is refused`() {
        val past = Date(System.currentTimeMillis() - 3_600_000)
        assertNull(verifier.verify(GoogleTokens.sign(google, expiresAt = past)))
    }

    @Test
    fun `a token signed by an unknown key is refused`() {
        val forged = GoogleTokens.key("google")
        assertNull(verifier.verify(GoogleTokens.sign(forged)))
    }

    @Test
    fun `garbage is refused`() {
        assertNull(verifier.verify("not-a-jwt"))
        assertNull(verifier.verify(""))
    }

    @Test
    fun `the client id comes from the environment, comma separated`() {
        assertEquals(emptySet(), GoogleSignInConfig.clientIds(emptyMap()))
        assertNull(GoogleSignInConfig.verifierFromEnv(emptyMap()))
        assertEquals(
            setOf("a.apps.googleusercontent.com", "b.apps.googleusercontent.com"),
            GoogleSignInConfig.clientIds(
                mapOf(GoogleSignInConfig.ENV to " a.apps.googleusercontent.com, b.apps.googleusercontent.com ,"),
            ),
        )
    }
}

/** Test helper that mints Google-shaped ID tokens with a local RSA key. */
object GoogleTokens {
    const val CLIENT_ID = "test-client.apps.googleusercontent.com"

    fun key(kid: String): RSAKey = RSAKeyGenerator(2048).keyID(kid).generate()

    fun keys(vararg keys: RSAKey): ImmutableJWKSet<SecurityContext> =
        ImmutableJWKSet(JWKSet(keys.map { it.toPublicJWK() }))

    fun sign(
        key: RSAKey,
        subject: String = "google-sub",
        nonce: String? = null,
        audience: String = CLIENT_ID,
        issuer: String = "https://accounts.google.com",
        expiresAt: Date = Date(System.currentTimeMillis() + 3_600_000),
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject)
            .issueTime(Date(System.currentTimeMillis() - 1_000))
            .expirationTime(expiresAt)
            .claim("email", "learner@example.com")
            .apply { if (nonce != null) claim("nonce", nonce) }
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()
    }
}
