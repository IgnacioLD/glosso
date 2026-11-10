package com.glosso.server.product

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sign in with Google decision logic without the HTTP layer: linking,
 * moving the account to a new phone and refusing a token minted elsewhere.
 */
class AccountServiceGoogleTest {

    private val now: Instant = Instant.parse("2026-03-01T00:00:00Z")

    private fun service(
        store: AccountStore,
        clock: () -> Instant = { now },
    ): AccountService = AccountService(
        store = store,
        clock = clock,
        accountSecret = AccountSecret(ByteArray(32) { 3 }),
        googleVerifier = verifier,
    )

    /** Stands in for Google: a token is "sub|nonce", and "bad" is rejected. */
    private val verifier = GoogleIdTokenVerifier { token ->
        if (token == "bad") null else GoogleIdentity(token.substringBefore('|'), token.substringAfter('|', "").ifEmpty { null })
    }

    private fun AccountService.googleToken(deviceId: String, subject: String): String =
        "$subject|${googleSignInNonce(deviceId)}"

    @Test
    fun `sign-in is off without the server secret`() {
        val service = AccountService(InMemoryAccountStore())

        assertEquals(false, service.googleSignInEnabled)
    }

    @Test
    fun `the first Google sign-in links the account where it is`() {
        val store = InMemoryAccountStore()
        val service = service(store)

        val result = service.signInWithGoogle("device-a", service.googleToken("device-a", "sub-1"))

        assertTrue(result is GoogleSignInResult.SignedIn, result.toString())
        assertEquals(false, result.moved)
        assertEquals(listOf("google"), result.me.linkedAccounts)
        assertNotNull(store.identityOf("device-a"))
    }

    @Test
    fun `signing in with Google on a new phone brings the account along`() {
        val store = InMemoryAccountStore()
        val service = service(store)
        service.signInWithGoogle("old-phone", service.googleToken("old-phone", "sub-1"))
        val account = service.identify("old-phone")

        val result = service.signInWithGoogle("new-phone", service.googleToken("new-phone", "sub-1"))

        assertTrue(result is GoogleSignInResult.SignedIn, result.toString())
        assertTrue(result.moved)
        assertEquals(account.id, store.identityOf("new-phone")?.id)
        assertNull(store.identityOf("old-phone"))
    }

    @Test
    fun `a Google token without this device's nonce is refused`() {
        val service = service(InMemoryAccountStore())
        val stolen = service.googleToken("other-device", "sub-1")

        assertEquals(GoogleSignInResult.InvalidToken, service.signInWithGoogle("device-a", stolen))
        assertEquals(GoogleSignInResult.InvalidToken, service.signInWithGoogle("device-a", "sub-1"))
        assertEquals(GoogleSignInResult.InvalidToken, service.signInWithGoogle("device-a", "bad"))
    }

    @Test
    fun `a device linked to one Google account cannot take another one`() {
        val service = service(InMemoryAccountStore())
        service.signInWithGoogle("device-a", service.googleToken("device-a", "sub-1"))
        service.signInWithGoogle("device-b", service.googleToken("device-b", "sub-2"))

        assertEquals(
            GoogleSignInResult.Conflict,
            service.signInWithGoogle("device-a", service.googleToken("device-a", "sub-2")),
        )
        assertEquals(
            GoogleSignInResult.Conflict,
            service.signInWithGoogle("device-a", service.googleToken("device-a", "sub-3")),
        )
    }
}
