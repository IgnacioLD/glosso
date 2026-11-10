package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** The F-Droid build has no Google sign-in and no Google libraries, and statistics start off. */
class FdroidFlavorTest {

    @Test
    fun `no sign-in is installed, so Change phone stays hidden`() {
        Flavor.install()

        assertSame(UnavailableSignIn, accountSignIn)
        assertFalse(accountSignIn.isConfigured)
    }

    @Test
    fun `no Google sign-in or Play services class is on the classpath`() {
        listOf(
            "com.google.android.libraries.identity.googleid.GetGoogleIdOption",
            "com.google.android.gms.common.GoogleApiAvailability",
            "androidx.credentials.playservices.CredentialProviderPlayServicesImpl",
        ).forEach { name ->
            try {
                Class.forName(name)
                fail("$name must not be in the F-Droid build")
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    @Test
    fun `usage statistics are off until the learner turns them on`() {
        assertEquals(false, BuildConfig.USAGE_STATISTICS_DEFAULT)
    }
}
