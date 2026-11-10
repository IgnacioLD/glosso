package com.glosso.app

/**
 * The F-Droid build ships no Google libraries, so it installs nothing: sign-in
 * stays [UnavailableSignIn] and "Change phone" is hidden.
 */
internal object Flavor {
    fun install() = Unit
}
