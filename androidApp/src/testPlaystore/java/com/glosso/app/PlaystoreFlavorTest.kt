package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The Google Play build keeps Sign in with Google and asks before sending statistics. */
class PlaystoreFlavorTest {

    @Test
    fun `Google sign-in is the account sign-in`() {
        Flavor.install()

        assertSame(GoogleSignIn, accountSignIn)
        assertEquals(BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank(), accountSignIn.isConfigured)
    }

    @Test
    fun `usage statistics stay off until the learner accepts the prompt`() {
        assertEquals(false, BuildConfig.USAGE_STATISTICS_DEFAULT)
        assertEquals(true, BuildConfig.USAGE_STATISTICS_ASK)
    }
}
