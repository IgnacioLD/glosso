package com.glosso.app

import com.glosso.app.platform.PlatformContext

actual val accountSignIn: AccountSignIn get() = UnavailableSignIn

/**
 * No sign-in on iOS yet: the option stays hidden. TODO(ios phase 5): Sign in
 * with Apple, verified by the server like Google's token today.
 */
private object UnavailableSignIn : AccountSignIn {
    override val isConfigured: Boolean = false

    override suspend fun pickAccount(context: PlatformContext, nonce: String): AccountPick = AccountPick.Cancelled
}
