package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * The browser's Back button, the iOS edge swipe in Safari and, on a desktop,
 * Esc. Every subscreen also keeps its on-screen back button.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val handler = { current.value() }
        BrowserBack.add(handler)
        onDispose { BrowserBack.remove(handler) }
    }
}

/**
 * While any handler is registered, one extra history entry sits on top, so
 * Back pops it instead of leaving the app; the innermost handler runs and the
 * entry is pushed again if a handler is still registered.
 * shortcut: the entry is not removed when the last handler goes away, so one
 * later Back press does nothing; remove it with history.back() if that shows.
 */
private object BrowserBack {
    private val handlers = mutableListOf<() -> Unit>()
    private var guarded = false

    init {
        window.addEventListener("popstate", { _: Event ->
            guarded = false
            handlers.lastOrNull()?.invoke()
            // After the screen that handled it has recomposed away.
            window.setTimeout({ guard(); null }, 0)
        })
        window.addEventListener("keydown", { event: Event ->
            if ((event as KeyboardEvent).key == "Escape" && !event.repeat) handlers.lastOrNull()?.invoke()
        })
    }

    fun add(handler: () -> Unit) {
        handlers += handler
        guard()
    }

    fun remove(handler: () -> Unit) {
        handlers -= handler
    }

    private fun guard() {
        if (handlers.isEmpty() || guarded) return
        window.history.pushState(null, "")
        guarded = true
    }
}
