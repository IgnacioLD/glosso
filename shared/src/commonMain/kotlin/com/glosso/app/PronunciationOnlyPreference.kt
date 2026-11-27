package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Pronunciation only", off by default: lessons open straight at their
 * pronunciation step, after any due review, skipping the story, the exercises
 * and "your turn". For the learner who comes to Glosso to train sounds. A lesson
 * with no drills still follows its whole flow.
 */
object PronunciationOnlyPreference {

    private const val FILE = "glosso_lessons"
    private const val KEY_ENABLED = "pronunciation_only"

    private var prefs: KeyValueStore? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _enabled.value = loaded.getBoolean(KEY_ENABLED, false)
    }

    fun set(context: PlatformContext, value: Boolean) {
        init(context)
        _enabled.value = value
        prefs?.edit { putBoolean(KEY_ENABLED, value) }
    }
}
