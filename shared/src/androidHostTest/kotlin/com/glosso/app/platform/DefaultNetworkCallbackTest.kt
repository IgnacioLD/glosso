package com.glosso.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The offline strip follows [AndroidConnectivity.online]. The regression: on
 * airplane mode the callback's `onLost` re-read the active network, which can
 * still report the lost network as validated for a moment, so about half the
 * time the strip never appeared. These tests keep that stale read in place
 * (the shadow manager still says "validated Wi-Fi") while the loss arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultNetworkCallbackTest {

    private val wifi: Network = ShadowNetwork.newInstance(100)
    private val cellular: Network = ShadowNetwork.newInstance(101)

    private fun caps(validated: Boolean): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return caps
    }

    @Test
    fun `losing the default network turns the strip on even while the active-network read is stale`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(manager)
        // The race as seen on the emulator: the system still answers "validated
        // Wi-Fi" to a direct read while the callback reports the loss.
        shadow.setNetworkCapabilities(manager.activeNetwork, caps(validated = true))

        AndroidConnectivity.init(context)
        val callback = shadow.networkCallbacks.single()
        val active = manager.activeNetwork!!
        callback.onAvailable(active)
        callback.onCapabilitiesChanged(active, caps(validated = true))
        assertTrue(AndroidConnectivity.online.value)

        callback.onLost(active)
        assertFalse("the strip must show as soon as the default network is lost", AndroidConnectivity.online.value)

        // Returning to the foreground must not put the stale "online" back.
        AndroidConnectivity.refresh()
        assertFalse(AndroidConnectivity.online.value)

        callback.onAvailable(active)
        callback.onCapabilitiesChanged(active, caps(validated = true))
        assertTrue("the strip must hide once a validated network is back", AndroidConnectivity.online.value)
    }

    @Test
    fun `a new default network is online only once it validates`() {
        val seen = mutableListOf<Boolean>()
        val callback = DefaultNetworkCallback { seen += it }

        callback.onAvailable(cellular)
        callback.onCapabilitiesChanged(cellular, caps(validated = false))
        callback.onCapabilitiesChanged(cellular, caps(validated = true))

        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `a late loss of the previous default does not hide its replacement`() {
        val seen = mutableListOf<Boolean>()
        val callback = DefaultNetworkCallback { seen += it }
        callback.onAvailable(cellular)
        callback.onCapabilitiesChanged(cellular, caps(validated = true))

        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        callback.onLost(cellular)

        assertEquals(listOf(true, true), seen)

        callback.onLost(wifi)
        assertEquals(listOf(true, true, false), seen)
    }
}
