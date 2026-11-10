package com.glosso.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `screen_view`: one event each time the screen the learner sees changes. The
 * shell sets the base screen (a tab, a Library route, the lesson player or the
 * story reader) and every sub-screen or sheet drawn over it registers itself with
 * [TrackScreen] while it is shown. The visible screen is the newest of those, or
 * the base when none is open, so closing a sheet reports the screen beneath again.
 *
 * Changes settle for a moment before they are reported: a rotation or an
 * animated hand-over briefly removes and re-adds the same screens, and must not
 * count as visits. A screen equal to the last one reported is never repeated.
 * Names must be in the server's `ClientEvents.SCREENS`.
 */
object ScreenTracker {

    private const val SETTLE_MS = 400L

    private var base: String? = null
    private val overlays = mutableListOf<Pair<Any, String>>()
    private var lastReported: String? = null

    /**
     * Runs [report] after the settle time on the main thread, replacing a report
     * still waiting; replaced in tests to run at once.
     */
    var schedule: (() -> Unit) -> Unit = { task ->
        pending?.cancel()
        pending = main.launch {
            delay(SETTLE_MS)
            task()
        }
    }

    private val main: CoroutineScope by lazy { MainScope() }
    private var pending: Job? = null
    private val report: () -> Unit = { reportNow() }

    /** The screen behind every sheet and sub-screen. */
    fun setBase(name: String) {
        if (base == name) return
        base = name
        schedule(report)
    }

    fun push(token: Any, name: String) {
        overlays.removeAll { it.first === token }
        overlays.add(token to name)
        schedule(report)
    }

    fun pop(token: Any) {
        if (overlays.removeAll { it.first === token }) schedule(report)
    }

    /** A new session reports the screen it starts on, even if it is the last one seen. */
    fun onNewSession() {
        lastReported = null
        schedule(report)
    }

    /** What the learner sees now: the newest open overlay, else the base. */
    fun visible(): String? = overlays.lastOrNull()?.second ?: base

    private fun reportNow() {
        val screen = visible() ?: return
        if (screen == lastReported) return
        lastReported = screen
        Analytics.track("screen_view", mapOf("screen" to screen))
    }

    fun resetForTest() {
        base = null
        overlays.clear()
        lastReported = null
    }
}

/** Marks [name] as the visible screen while this composable is shown. */
@Composable
fun TrackScreen(name: String) {
    DisposableEffect(name) {
        val token = Any()
        ScreenTracker.push(token, name)
        onDispose { ScreenTracker.pop(token) }
    }
}
