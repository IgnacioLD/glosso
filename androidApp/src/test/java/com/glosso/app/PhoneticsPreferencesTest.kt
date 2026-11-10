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
class PhoneticsPreferencesTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `written pronunciation is on by default and can be turned off`() {
        app.getSharedPreferences("glosso_display", Context.MODE_PRIVATE).edit().clear().commit()
        PhoneticsPreferences.init(app)

        assertTrue("the IPA shows by default", PhoneticsPreferences.visible.value)

        PhoneticsPreferences.setVisible(app, false)
        assertFalse(PhoneticsPreferences.visible.value)
        assertFalse(
            app.getSharedPreferences("glosso_display", Context.MODE_PRIVATE)
                .getBoolean("show_phonetics", true),
        )

        // Turning it back on is remembered too.
        PhoneticsPreferences.setVisible(app, true)
        assertTrue(PhoneticsPreferences.visible.value)
    }
}
