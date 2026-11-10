package com.glosso.app.download

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Download only on Wi-Fi", on by default. A large model or a full lesson on a
 * metered plan is the kind of surprise a learner should never get, so the safe
 * choice is the default and the app pauses (and says so) on mobile data instead
 * of spending their megabytes. The learner can still allow it per download from
 * the notification.
 */
object WifiOnlyPreference {

    private const val FILE = "glosso_downloads"
    private const val KEY_WIFI_ONLY = "wifi_only"

    private var prefs: KeyValueStore? = null

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _enabled.value = loaded.getBoolean(KEY_WIFI_ONLY, true)
    }

    fun set(context: PlatformContext, value: Boolean) {
        init(context)
        _enabled.value = value
        prefs?.edit { putBoolean(KEY_WIFI_ONLY, value) }
    }
}
