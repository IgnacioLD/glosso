package com.glosso.app.platform

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_global_queue
import platform.posix.QOS_CLASS_UTILITY

actual fun platformConnectivity(context: PlatformContext): Connectivity = IosConnectivity.also { it.start() }

/**
 * The default path through `NWPathMonitor`. "Online" is a satisfied path; a path
 * is metered when the system calls it expensive (cellular, a personal hotspot)
 * or constrained (Low Data Mode), the same care Android gives a metered network.
 * TODO(iOS phase 3): verify on a device that the first update lands before the
 * first screen reads [isOnline] (until then it reads as online).
 */
internal object IosConnectivity : Connectivity {

    private val _online = MutableStateFlow(!IosDebugEnv.offline)

    override val online: StateFlow<Boolean> = _online.asStateFlow()

    @Volatile
    private var unmetered = false

    private var monitor: nw_path_monitor_t = null

    fun start() {
        if (monitor != null) return
        val created = nw_path_monitor_create()
        monitor = created
        nw_path_monitor_set_update_handler(created) { path ->
            _online.value = !IosDebugEnv.offline && nw_path_get_status(path) == nw_path_status_satisfied
            unmetered = !nw_path_is_expensive(path) && !nw_path_is_constrained(path)
        }
        nw_path_monitor_set_queue(created, dispatch_get_global_queue(QOS_CLASS_UTILITY.toLong(), 0u))
        nw_path_monitor_start(created)
    }

    override fun isOnline(): Boolean = _online.value

    override fun isUnmetered(): Boolean = unmetered

    // The monitor pushes every change, so there is nothing to re-read.
    override fun refresh() = Unit
}
