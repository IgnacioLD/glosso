package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Days without a scored take before the gentle come-back note shows. */
const val REMINDER_AFTER_DAYS = 2

/**
 * Days since the last scored take, or null when the learner has never recorded
 * one. Pure so the welcome-back rule is tested without a clock.
 */
fun daysSinceLastActive(lastActiveDay: Long, today: Long): Int? =
    if (lastActiveDay <= 0L) null else (today - lastActiveDay).coerceAtLeast(0L).toInt()

/**
 * The optional, non-punishing come-back nudge. There is no push, no streak
 * shaming and no server: when the learner opts in, Today quietly notes how many
 * days passed since the last scored take. It is a local preference, so it can
 * later back a real local notification without changing this contract.
 */
object DailyReminder {

    private const val FILE = "glosso_reminder"
    private const val KEY_ENABLED = "enabled"

    private var prefs: KeyValueStore? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Reads the saved choice once; safe to call from any screen. */
    fun ensureInitialized(context: PlatformContext) {
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _enabled.value = loaded.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: PlatformContext, value: Boolean) {
        ensureInitialized(context)
        _enabled.value = value
        prefs?.edit { putBoolean(KEY_ENABLED, value) }
    }
}
