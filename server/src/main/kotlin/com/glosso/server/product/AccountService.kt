package com.glosso.server.product

import com.glosso.server.api.MeResponse
import java.time.Instant

/** The outcome of [AccountService.signInWithGoogle]. */
sealed interface GoogleSignInResult {
    /** Signed in; [moved] is true when the account came from another device. */
    data class SignedIn(val me: MeState, val moved: Boolean) : GoogleSignInResult

    /** The token is not a valid Google ID token for this server and device. */
    data object InvalidToken : GoogleSignInResult

    /** This device's account is already linked to a different Google account. */
    data object Conflict : GoogleSignInResult
}

/** The server-computed identity snapshot served by `GET /v1/me`. */
data class MeState(
    val linkedAccounts: List<String> = emptyList(),
)

fun MeState.toResponse(): MeResponse = MeResponse(linkedAccounts = linkedAccounts)

/**
 * Device identity and Sign in with Google. Everything in the app is free, so
 * an account only exists to carry a learner's profile to a new phone.
 */
class AccountService(
    private val store: AccountStore,
    private val clock: () -> Instant = Instant::now,
    /** Keys sign-in nonces; null disables Sign in with Google. */
    private val accountSecret: AccountSecret? = null,
    /** Verifies Google ID tokens; null disables Sign in with Google. */
    private val googleVerifier: GoogleIdTokenVerifier? = null,
) {
    /** Sign in with Google needs a client id to verify against and the nonce secret. */
    val googleSignInEnabled: Boolean get() = accountSecret != null && googleVerifier != null

    fun identify(deviceId: String): UserIdentity = store.upsertUser(deviceId, clock()).identity

    /**
     * A fresh nonce for Sign in with Google on [deviceId]. The app hands it to
     * Credential Manager, Google signs it into the ID token, and
     * [signInWithGoogle] only accepts a token carrying a nonce issued for the
     * same device, so a token cannot be replayed from another device.
     */
    fun googleSignInNonce(deviceId: String): String {
        val secret = checkNotNull(accountSecret) { "sign-in is not configured" }
        return secret.signInNonce(deviceId, clock())
    }

    /**
     * Signs [deviceId] in with a Google ID token chosen in the system account
     * picker. The first sign-in links the Google account to this device's
     * account; a sign-in on another device moves the account there. Only the
     * token's opaque `sub` is kept.
     */
    fun signInWithGoogle(deviceId: String, idToken: String): GoogleSignInResult {
        val secret = checkNotNull(accountSecret) { "sign-in is not configured" }
        val verifier = checkNotNull(googleVerifier) { "sign-in is not configured" }
        val google = verifier.verify(idToken) ?: return GoogleSignInResult.InvalidToken
        val nonce = google.nonce ?: return GoogleSignInResult.InvalidToken
        if (!secret.isValidSignInNonce(nonce, deviceId, clock())) return GoogleSignInResult.InvalidToken
        // The device row must exist before it can be linked or merged into an account.
        identify(deviceId)
        return when (val result = store.signInWithExternalAccount(GOOGLE_PROVIDER, google.subject, deviceId, clock())) {
            ExternalSignIn.Conflict -> GoogleSignInResult.Conflict
            is ExternalSignIn.SignedIn -> GoogleSignInResult.SignedIn(me(deviceId), result.moved)
        }
    }

    fun me(deviceId: String): MeState = MeState(linkedAccounts = store.linkedProviders(identify(deviceId).id))

    companion object {
        const val GOOGLE_PROVIDER = "google"
    }
}
