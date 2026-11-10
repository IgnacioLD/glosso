package com.glosso.server.billing

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The fields of a Google Cloud service account key the server actually uses.
 * Unknown members are ignored, so the whole downloaded JSON can be passed in.
 */
@Serializable
data class ServiceAccountKey(
    @SerialName("client_email") val clientEmail: String = "",
    @SerialName("private_key") val privateKey: String = "",
    @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): ServiceAccountKey =
            json.decodeFromString<ServiceAccountKey>(text).also { key ->
                require(key.clientEmail.isNotBlank()) { "service account JSON is missing client_email" }
                require(key.privateKey.isNotBlank()) { "service account JSON is missing private_key" }
            }
    }
}

@Serializable
private data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

/**
 * Exchanges a service account key for short-lived OAuth2 access tokens for the
 * Android Publisher API. The RS256 JWT-bearer assertion is signed with the JDK
 * (no extra dependency) and the access token is cached until shortly before it
 * expires, so a burst of verifications costs one token request.
 */
class ServiceAccountTokenProvider(
    private val key: ServiceAccountKey,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val privateKey: PrivateKey = parsePrivateKey(key.privateKey)
    private val mutex = Mutex()
    private val cached = AtomicReference<CachedToken?>()

    private data class CachedToken(val value: String, val expiresAtMillis: Long)

    suspend fun accessToken(): String {
        val now = clock()
        cached.get()?.let { if (it.expiresAtMillis - 60_000 > now) return it.value }
        return mutex.withLock {
            val fresh = clock()
            cached.get()?.let { if (it.expiresAtMillis - 60_000 > fresh) return it.value }
            val token = withContext(Dispatchers.IO) { exchange(jwtAssertion(fresh)) }
            cached.set(CachedToken(token.accessToken, fresh + token.expiresIn * 1000))
            token.accessToken
        }
    }

    private fun jwtAssertion(nowMillis: Long): String {
        val issuedAt = nowMillis / 1000
        val claims = buildJsonObject {
            put("iss", key.clientEmail)
            put("scope", ANDROID_PUBLISHER_SCOPE)
            put("aud", key.tokenUri)
            put("iat", issuedAt)
            put("exp", issuedAt + 3600)
        }.toString()
        val signingInput = "${b64(HEADER.toByteArray())}.${b64(claims.toByteArray())}"
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray())
        }
        return "$signingInput.${b64(signer.sign())}"
    }

    private fun exchange(assertion: String): OAuthTokenResponse {
        val form = "grant_type=${enc("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${enc(assertion)}"
        val request = HttpRequest.newBuilder(URI.create(key.tokenUri))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw PlayApiException(
                "Google OAuth token request failed (${response.statusCode()})",
                response.statusCode(),
            )
        }
        return json.decodeFromString<OAuthTokenResponse>(response.body())
    }

    private companion object {
        const val ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
        const val HEADER = """{"alg":"RS256","typ":"JWT"}"""

        fun b64(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun enc(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        fun parsePrivateKey(pem: String): PrivateKey {
            val base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .filterNot { it.isWhitespace() }
            val bytes = try {
                Base64.getDecoder().decode(base64)
            } catch (e: IllegalArgumentException) {
                throw BillingNotConfiguredException("service account private_key is not valid base64")
            }
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
            } catch (e: Exception) {
                throw BillingNotConfiguredException("service account private_key is not a PKCS#8 RSA key")
            }
        }
    }
}
