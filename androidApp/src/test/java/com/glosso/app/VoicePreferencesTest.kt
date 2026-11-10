package com.glosso.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The voice is the content's, not the learner's: only the accent is stored, and
 * the gender older versions saved is dropped without disturbing the accent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicePreferencesTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("glosso_voice", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        app.getSharedPreferences("glosso_language", Context.MODE_PRIVATE).edit().clear().commit()
        LanguagePreferences.init(app)
        prefs.edit().clear().commit()
    }

    @Test
    fun `an old stored gender is dropped and the accent is kept`() {
        prefs.edit().putString("accent", "en-US").putString("gender", "male").commit()

        VoicePreferences.init(app)

        assertEquals("en-US", VoicePreferences.accent)
        assertFalse(prefs.contains("gender"))
    }

    @Test
    fun `setting the accent persists only the accent`() {
        VoicePreferences.init(app)

        VoicePreferences.set("en-GB")

        assertEquals("en-GB", VoicePreferences.accent)
        assertEquals(setOf("accent"), prefs.all.keys)
        assertEquals("en-GB", prefs.getString("accent", null))
    }
}
