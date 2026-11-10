package com.glosso.server

import com.glosso.server.api.GoogleSignInRequest
import com.glosso.server.product.AccountSecret
import com.glosso.server.product.ClientConfig
import com.glosso.server.product.GoogleTokens
import com.glosso.server.product.InMemoryAccountStore
import com.glosso.server.product.NimbusGoogleIdTokenVerifier
import com.glosso.server.product.ProductConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sign in with Google end to end through HTTP: nonce, a signed ID token (with
 * a local key standing in for Google's) and the account following the person
 * to a new phone.
 */
class GoogleSignInRouteTest {

    private val google = GoogleTokens.key("google")
    private val secret = AccountSecret(ByteArray(32) { 4 })
    private val verifier = NimbusGoogleIdTokenVerifier(setOf(GoogleTokens.CLIENT_ID), GoogleTokens.keys(google))

    private val config = ProductConfig(flags = emptyMap(), client = ClientConfig(2210, 2219, false))

    /** An account first seen on "old-phone". */
    private fun storeWithOldPhone(): InMemoryAccountStore {
        val store = InMemoryAccountStore()
        store.upsertUser("old-phone", Instant.parse("2026-03-01T00:00:00Z"))
        return store
    }

    private fun ApplicationTestBuilder.app(store: InMemoryAccountStore, withGoogle: Boolean = true) {
        application {
            module(
                Files.createTempDirectory("glosso-google-assets").toFile(),
                adminToken = null,
                analytics = null,
                accountStore = store,
                productConfig = config,
                accountSecret = secret,
                googleVerifier = verifier.takeIf { withGoogle },
            )
        }
    }

    private suspend fun nonce(client: HttpClient, deviceId: String): String {
        val response = client.post("/v1/auth/google/nonce") { header("X-Device-Id", deviceId) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("nonce").jsonPrimitive.content
    }

    private fun linkedAccounts(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject.getValue("linkedAccounts").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun signIn(client: HttpClient, deviceId: String, token: String) =
        client.post("/v1/auth/google") {
            header("X-Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest(token))
        }

    @Test
    fun `signing in with Google on a new phone moves the account there`() = testApplication {
        val store = storeWithOldPhone()
        val account = assertNotNull(store.identityOf("old-phone"))
        app(store)
        val client = createClient { install(ContentNegotiation) { json() } }

        val first = signIn(client, "old-phone", GoogleTokens.sign(google, "sub-1", nonce(client, "old-phone")))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(listOf("google"), linkedAccounts(first.bodyAsText()))

        val moved = signIn(client, "new-phone", GoogleTokens.sign(google, "sub-1", nonce(client, "new-phone")))
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        val body = moved.bodyAsText()
        assertEquals("no-store", moved.headers["Cache-Control"])
        // No personal data from the token reaches the answer.
        assertTrue("learner@example.com" !in body, body)
        assertEquals(account.id, store.identityOf("new-phone")?.id)
        assertNull(store.identityOf("old-phone"))
        assertEquals(listOf("google"), linkedAccounts(body))
        // The old phone is a fresh anonymous user now: /v1/me says it is linked
        // to nothing, so the app drops its stale "linked to ..." line.
        val oldPhone = client.get("/v1/me") { header("X-Device-Id", "old-phone") }
        assertEquals(emptyList(), linkedAccounts(oldPhone.bodyAsText()))
    }

    @Test
    fun `a token minted for another device is refused`() = testApplication {
        app(storeWithOldPhone())
        val client = createClient { install(ContentNegotiation) { json() } }

        val stolen = GoogleTokens.sign(google, "sub-1", nonce(client, "old-phone"))

        assertEquals(HttpStatusCode.Unauthorized, signIn(client, "attacker-phone", stolen).status)
        assertEquals(HttpStatusCode.Unauthorized, signIn(client, "old-phone", GoogleTokens.sign(google, "sub-1")).status)
        val forged = GoogleTokens.sign(GoogleTokens.key("google"), "sub-1", nonce(client, "old-phone"))
        assertEquals(HttpStatusCode.Unauthorized, signIn(client, "old-phone", forged).status)
    }

    @Test
    fun `a phone linked to one Google account answers conflict for another`() = testApplication {
        app(storeWithOldPhone())
        val client = createClient { install(ContentNegotiation) { json() } }

        signIn(client, "old-phone", GoogleTokens.sign(google, "sub-1", nonce(client, "old-phone")))
        val other = signIn(client, "old-phone", GoogleTokens.sign(google, "sub-2", nonce(client, "old-phone")))

        assertEquals(HttpStatusCode.Conflict, other.status)
    }

    @Test
    fun `sign-in is unavailable without a Google client id`() = testApplication {
        app(storeWithOldPhone(), withGoogle = false)
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            client.post("/v1/auth/google/nonce") { header("X-Device-Id", "old-phone") }.status,
        )
        assertEquals(HttpStatusCode.ServiceUnavailable, signIn(client, "old-phone", "token").status)
    }

    @Test
    fun `sign-in requires a device id and a token`() = testApplication {
        app(storeWithOldPhone())
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/auth/google/nonce").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/v1/auth/google") {
                header("X-Device-Id", "old-phone")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.status,
        )
    }
}
