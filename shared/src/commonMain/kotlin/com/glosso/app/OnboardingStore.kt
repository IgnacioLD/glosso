package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the first-run welcome has already been seen. Persisted so it shows
 * once, on the very first launch, and never gets in the way again. Defaults to
 * done until [init] runs, so nothing flashes before the real value is read.
 */
object OnboardingStore {
    private const val FILE = "glosso_onboarding"
    private const val KEY_DONE = "done"

    private lateinit var prefs: KeyValueStore

    private val _done = MutableStateFlow(true)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun init(context: PlatformContext) {
        prefs = keyValueStore(context, FILE)
        _done.value = prefs.getBoolean(KEY_DONE, false)
    }

    fun complete() {
        _done.value = true
        if (::prefs.isInitialized) prefs.edit { putBoolean(KEY_DONE, true) }
    }
}
