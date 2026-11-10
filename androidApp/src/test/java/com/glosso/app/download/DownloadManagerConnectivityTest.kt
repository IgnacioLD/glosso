package com.glosso.app.download

import android.content.Context
import android.net.ConnectivityManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The regression guard for the bug that made this service useless on mobile
 * data: if the network callback is never registered, an item parked in
 * WAITING_NETWORK never wakes when Wi-Fi comes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadManagerConnectivityTest {

    @Test
    fun `init registers a connectivity callback that can wake parked items`() {
        val context = RuntimeEnvironment.getApplication()
        DownloadManager.resetForTest()

        DownloadManager.init(context)

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertTrue(
            "init must register a default network callback",
            shadowOf(manager).networkCallbacks.isNotEmpty(),
        )
    }
}
