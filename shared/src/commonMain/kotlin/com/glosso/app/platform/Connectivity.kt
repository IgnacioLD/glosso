package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow

/**
 * One honest answer to "can we reach the server right now", as a one-shot check
 * and as a flow the UI can follow. The flow is what lets the app say, soberly,
 * that it is offline and recover the moment the connection is back, without the
 * learner having to restart or hammer a retry.
 *
 * Android reads `ConnectivityManager` (a validated default network); iOS follows
 * an `NWPathMonitor`. There is one instance per process: [platformConnectivity]
 * starts watching on its first call and returns the same object after that.
 */
interface Connectivity {
    /**
     * True while the active network can reach the internet. Starts optimistic so
     * a screen never flashes the offline state before the first real check lands.
     */
    val online: StateFlow<Boolean>

    /** True when the active network can reach the internet, read now. */
    fun isOnline(): Boolean

    /**
     * True when the active network is unmetered (Wi-Fi or Ethernet, or a metered
     * Wi-Fi the user marked unmetered). Used by the "only on Wi-Fi" download
     * policy: anything that is not unmetered counts as mobile data, so a tether
     * or a metered hotspot is treated with the same care.
     */
    fun isUnmetered(): Boolean

    /**
     * Re-reads the connection now. Called when the app returns to the foreground,
     * so a change that happened while backgrounded (or a callback the platform
     * swallowed) is corrected instead of leaving a stale banner.
     */
    fun refresh()
}

/** The process's [Connectivity]; the first call starts watching the network. */
expect fun platformConnectivity(context: PlatformContext): Connectivity

/** [platformConnectivity] for the composition's context. */
@Composable
fun rememberConnectivity(): Connectivity {
    val context = currentPlatformContext()
    return remember(context) { platformConnectivity(context) }
}
