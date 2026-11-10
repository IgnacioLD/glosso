package com.glosso.app

import com.glosso.app.platform.PlatformContext

/** What the system account picker returned. */
sealed interface AccountPick {
    /** An account was chosen: its ID token (for the server) and its email (shown here only). */
    data class Picked(val idToken: String, val email: String) : AccountPick

    /** The learner closed the sheet. Not an error. */
    data object Cancelled : AccountPick

    /** No account on the device (or none usable). */
    data object NoAccount : AccountPick

    data object Failed : AccountPick
}

/** The `result` values of the `sign_in` event (docs/ANALYTICS-BETA.md). */
object SignInResult {
    const val SUCCESS = "success"
    const val SERVER_ERROR = "server_error"
    const val CANCELLED = "cancelled"
    const val NO_ACCOUNT = "no_account"
    const val FAILED = "failed"
}

/** The `sign_in` result of a pick that ends the attempt; null for [AccountPick.Picked], which goes on to the server. */
val AccountPick.signInResult: String?
    get() = when (this) {
        is AccountPick.Picked -> null
        AccountPick.Cancelled -> SignInResult.CANCELLED
        AccountPick.NoAccount -> SignInResult.NO_ACCOUNT
        AccountPick.Failed -> SignInResult.FAILED
    }

/**
 * Signing in with the platform's account: Google through Credential Manager on
 * Android. Only the identity is requested (an ID token), never any other scope.
 */
interface AccountSignIn {
    /** False when the build cannot sign in; the option is then hidden. */
    val isConfigured: Boolean

    /**
     * Opens the account picker. On Android [context] must be an Activity context
     * (the sheet is UI). [nonce] comes from the server and binds the token to
     * this device.
     */
    suspend fun pickAccount(context: PlatformContext, nonce: String): AccountPick
}

/** This platform's [AccountSignIn]. */
expect val accountSignIn: AccountSignIn
