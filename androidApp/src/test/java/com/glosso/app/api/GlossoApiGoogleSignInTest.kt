package com.glosso.app.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sign in with Google calls the "change phone" flow uses: a device-bound
 * nonce and the ID token. Both must carry `X-Device-Id` (the account is
 * device-keyed) and decode the server shapes.
 */
class GlossoApiGoogleSignInTest {

    @Test
    fun `google sign-in fetches a device nonce and posts the id token`() = runTest {
        val calls = mutableListOf<String>()
        var device: String? = null
        var body: String? = null
        val engine = MockEngine { request ->
            calls += request.url.encodedPath
            device = request.headers["X-Device-Id"]
            if (request.url.encodedPath.endsWith("/nonce")) {
                respond(
                    """{"nonce":"123.abc"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                body = (request.body as? TextContent)?.text
                respond(
                    """{"linkedAccounts":["google"]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null, deviceIdProvider = { "device-9" })

        assertEquals("123.abc", api.googleSignInNonce())
        val identity = api.signInWithGoogle("header.payload.signature")

        assertEquals(listOf("/v1/auth/google/nonce", "/v1/auth/google"), calls)
        assertEquals("device-9", device)
        assertTrue(body ?: "no body", body?.contains("\"idToken\":\"header.payload.signature\"") == true)
        assertEquals(listOf("google"), identity.linkedAccounts)
    }

    @Test
    fun `a google sign-in conflict keeps its status for the screen`() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":"This device is linked to another Google account"}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = GlossoApi(baseUrl = "http://test", engine = engine, contentCache = null, deviceIdProvider = { "device-9" })

        val failure = runCatching { api.signInWithGoogle("token") }.exceptionOrNull()

        assertEquals(409, (failure as? ApiStatusException)?.status)
    }
}
