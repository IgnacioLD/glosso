package com.glosso.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationPreferencesTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the meaning is hidden by default and the reveal is remembered`() {
        app.getSharedPreferences("glosso_display", Context.MODE_PRIVATE).edit().clear().commit()
        TranslationPreferences.init(app)

        assertFalse("the translation is hidden by default", TranslationPreferences.visible.value)

        TranslationPreferences.setVisible(app, true)
        assertTrue(TranslationPreferences.visible.value)
        assertTrue(
            "the choice is saved",
            app.getSharedPreferences("glosso_display", Context.MODE_PRIVATE)
                .getBoolean("show_translations", false),
        )

        TranslationPreferences.setVisible(app, false)
        assertFalse(TranslationPreferences.visible.value)
    }
}
