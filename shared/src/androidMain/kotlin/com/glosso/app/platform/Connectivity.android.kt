package com.glosso.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun platformConnectivity(context: PlatformContext): Connectivity =
    AndroidConnectivity.also { it.init(context) }

/** A validated default network, through `ConnectivityManager`. */
internal object AndroidConnectivity : Connectivity {

    private val _online = MutableStateFlow(true)

    override val online: StateFlow<Boolean> = _online.asStateFlow()

    @Volatile
    private var app: Context? = null

    @Volatile
    private var registered = false

    /** Registers the system callback once. Safe to call from every entry point. */
    fun init(context: Context) {
        if (app == null) app = context.applicationContext
        if (registered) return
        val app = context.applicationContext
        _online.value = isOnline(app)
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        // Only mark registration done when the callback is actually in place, so
        // a missing manager or a failed registration is retried on the next entry
        // point instead of leaving the app blind to connectivity for good.
        runCatching {
            manager.registerDefaultNetworkCallback(DefaultNetworkCallback { _online.value = it })
        }.onSuccess {
            registered = true
        }.onFailure {
            android.util.Log.w("GlossoNetwork", "could not register the connectivity callback", it)
        }
    }

    /**
     * Once the callback is in place it is the only source for [online]: it sees
     * every change in order, and the system queues them for a backgrounded or
     * frozen process. A direct read of the active network can lag the callback
     * (right after a loss it can still return the lost network as validated), so
     * mixing it in could put a stale "online" back over the offline strip.
     * Without a callback, the direct read is the best there is.
     */
    override fun refresh() {
        val context = app ?: return
        if (!registered) init(context)
        if (!registered) _online.value = isOnline(context)
    }

    override fun isOnline(): Boolean = app?.let(::isOnline) ?: true

    override fun isUnmetered(): Boolean = app?.let(::isUnmetered) ?: false

    /** True when the active network is validated as having internet. */
    private fun isOnline(context: Context): Boolean {
        val capabilities = capabilities(context) ?: return noService(context)
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isUnmetered(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false

    private fun capabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return null
        return manager.getNetworkCapabilities(network)
    }

    /** No connectivity service at all (tests, odd devices): assume reachable. */
    private fun noService(context: Context): Boolean =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) !is ConnectivityManager
}

/**
 * Follows the default network from the callback's own arguments only.
 *
 * It never asks `ConnectivityManager` for the active network: that read races
 * the callback, and right after airplane mode drops the last network it can
 * still return the lost network as validated. Reading it in `onLost` is what
 * left the offline strip hidden about half the time.
 *
 * For a default network callback, `onLost` means there is no default network
 * any more (a switch to another network arrives as `onAvailable` for it), and
 * every `onAvailable` is followed by `onCapabilitiesChanged` (API 26+), which
 * is where the validated state is read.
 */
internal class DefaultNetworkCallback(
    private val publish: (online: Boolean) -> Unit,
) : ConnectivityManager.NetworkCallback() {

    private var current: Network? = null

    override fun onAvailable(network: Network) {
        current = network
    }

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        current = network
        publish(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    override fun onLost(network: Network) {
        // A late loss of a network that is no longer the default says nothing
        // about the one that replaced it.
        if (current != null && network != current) return
        current = null
        publish(false)
    }
}
