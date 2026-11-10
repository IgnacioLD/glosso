package com.glosso.app

import com.glosso.app.api.GlossoApi
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlin.concurrent.Volatile

/**
 * Whether the server scores against the accent systems (`GET /v1/accents`). The
 * on-device scorer follows it, so a take scores the same in the cloud and on the
 * phone whichever way the server is configured. The last answer is kept across
 * launches, because the on-device scorer is exactly what runs with no network;
 * before the first answer it is off, the behaviour of a server that predates it.
 */
object AccentScoringFlag {

    private const val FILE = "glosso_accent_scoring"
    private const val KEY = "multi_accent"

    private var prefs: KeyValueStore? = null

    @Volatile
    var enabled: Boolean = false
        private set

    fun init(context: PlatformContext) {
        val store = keyValueStore(context, FILE)
        prefs = store
        enabled = store.getBoolean(KEY, false)
    }

    /** Asks the server; a failed call keeps the last known answer. */
    suspend fun refresh(api: GlossoApi) {
        val answer = runCatching { api.accentScoring().multiAccentScoring }.getOrNull() ?: return
        enabled = answer
        prefs?.edit { putBoolean(KEY, answer) }
    }
}
