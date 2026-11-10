package com.glosso.app

/** What only the Google Play build wires: Sign in with Google for "Change phone". */
internal object Flavor {
    fun install() {
        GoogleSignIn.serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID
        accountSignIn = GoogleSignIn
    }
}
