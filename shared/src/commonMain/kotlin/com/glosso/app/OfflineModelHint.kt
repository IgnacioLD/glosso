package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the learner has spent the "download the model to keep practising
 * offline" tip that appears while offline. It is a one-time offer, not a loop:
 * once hidden, or once the download has been started from any surface, it never
 * comes back, and the model stays reachable from Settings and the offline drawer.
 */
object OfflineModelHint {

    private const val FILE = "glosso_offline"
    private const val KEY_DISMISSED = "model_hint_dismissed"

    private var prefs: KeyValueStore? = null

    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _dismissed.value = loaded.getBoolean(KEY_DISMISSED, false)
    }

    /** Spends the tip: the learner closed it. */
    fun dismiss(context: PlatformContext) = markSeen(context)

    /**
     * Spends the tip for any reason. Also called when the model download starts,
     * so a later cancel or failure does not make the offer pop back up.
     */
    fun markSeen(context: PlatformContext) {
        init(context)
        _dismissed.value = true
        prefs?.edit { putBoolean(KEY_DISMISSED, true) }
    }
}
