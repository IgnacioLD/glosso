package com.glosso.app.platform

import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.events.Event

actual fun platformConnectivity(context: PlatformContext): Connectivity = WebConnectivity.also { it.start() }

/**
 * `navigator.onLine` and its `online`/`offline` events. A browser cannot tell
 * whether the network is metered, and the web build downloads nothing for
 * offline use, so every network counts as unmetered.
 */
private object WebConnectivity : Connectivity {
    private val _online = MutableStateFlow(window.navigator.onLine)
    private var started = false

    override val online: StateFlow<Boolean> = _online.asStateFlow()

    fun start() {
        if (started) return
        started = true
        window.addEventListener("online", { _: Event -> refresh() })
        window.addEventListener("offline", { _: Event -> refresh() })
    }

    override fun isOnline(): Boolean = _online.value

    override fun isUnmetered(): Boolean = true

    override fun refresh() {
        _online.value = window.navigator.onLine
    }
}
