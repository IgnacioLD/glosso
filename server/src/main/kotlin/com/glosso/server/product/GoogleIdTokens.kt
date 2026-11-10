package com.glosso.server.product

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI

/** The only thing kept from a verified Google ID token: its opaque subject and nonce. */
data class GoogleIdentity(val subject: String, val nonce: String?)

/** Verifies a Google ID token; null when the token is not valid for this server. */
fun interface GoogleIdTokenVerifier {
    fun verify(idToken: String): GoogleIdentity?
}

/**
 * Checks a Google ID token the way Google documents it
 * (developers.google.com/identity/gsi/web/guides/verify-google-id-token):
 * an RS256 signature by one of Google's published keys, an `aud` equal to one
 * of our OAuth client ids, an `iss` of `accounts.google.com`, and a token that
 * has not expired. Google's keys rotate; the JWK source caches them and
 * refetches on an unknown key id.
 *
 * Only `sub` and `nonce` are read. The email, name and picture Google may put
 * in the token are never read, logged or stored.
 */
class NimbusGoogleIdTokenVerifier(
    clientIds: Set<String>,
    keys: JWKSource<SecurityContext> = googleKeys(),
) : GoogleIdTokenVerifier {

    init {
        require(clientIds.isNotEmpty()) { "at least one Google client id is required" }
    }

    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
            clientIds,
            null,
            setOf("sub", "iss", "exp", "iat"),
            null,
        )
    }

    override fun verify(idToken: String): GoogleIdentity? {
        val claims: JWTClaimsSet = runCatching { processor.process(idToken, null) }.getOrNull() ?: return null
        if (claims.issuer !in ISSUERS) return null
        val subject = claims.subject?.takeIf { it.isNotBlank() && it.length <= MAX_SUBJECT } ?: return null
        val nonce = runCatching { claims.getStringClaim("nonce") }.getOrNull()
        return GoogleIdentity(subject, nonce)
    }

    companion object {
        private val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")

        /** Google's `sub` is at most 255 ASCII characters. */
        private const val MAX_SUBJECT = 255

        private const val GOOGLE_CERTS = "https://www.googleapis.com/oauth2/v3/certs"

        fun googleKeys(): JWKSource<SecurityContext> =
            JWKSourceBuilder.create<SecurityContext>(URI(GOOGLE_CERTS).toURL())
                .retrying(true)
                .build()
    }
}

/**
 * Sign in with Google, configured from the environment. `GLOSSO_GOOGLE_CLIENT_ID`
 * is the **Web** OAuth client id of the Google Cloud project (the app passes the
 * same value as `serverClientId`); a comma-separated list is accepted to rotate
 * clients. Unset means sign-in is off and the endpoint answers 503. See
 * docs/GOOGLE-SIGNIN.md for how to create it.
 */
object GoogleSignInConfig {
    const val ENV = "GLOSSO_GOOGLE_CLIENT_ID"

    fun clientIds(env: Map<String, String> = System.getenv()): Set<String> =
        env[ENV].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun verifierFromEnv(env: Map<String, String> = System.getenv()): GoogleIdTokenVerifier? =
        clientIds(env).takeIf { it.isNotEmpty() }?.let { NimbusGoogleIdTokenVerifier(it) }
}
