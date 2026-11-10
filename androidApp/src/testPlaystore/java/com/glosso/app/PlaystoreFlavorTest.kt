package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The Google Play build keeps Sign in with Google and statistics on by default, as before. */
class PlaystoreFlavorTest {

    @Test
    fun `Google sign-in is the account sign-in`() {
        Flavor.install()

        assertSame(GoogleSignIn, accountSignIn)
        assertEquals(BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank(), accountSignIn.isConfigured)
    }

    @Test
    fun `usage statistics keep their default on`() {
        assertEquals(true, BuildConfig.USAGE_STATISTICS_DEFAULT)
    }
}
