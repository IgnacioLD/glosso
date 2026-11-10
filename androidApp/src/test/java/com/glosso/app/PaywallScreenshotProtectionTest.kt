package com.glosso.app

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import com.glosso.app.platform.setScreenshotProtected
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The paywall is the only screen that must not be capturable (M7). This pins the
 * flag toggling the paywall composable calls: on while composed, off on dispose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaywallScreenshotProtectionTest {

    private fun secureFlagOf(activity: Activity): Int =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test
    fun `protects the paywall window and restores it on dispose`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals("a fresh window is not secure", 0, secureFlagOf(activity))

        activity.window.setScreenshotProtected(true)
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, secureFlagOf(activity))

        activity.window.setScreenshotProtected(false)
        assertEquals("leaving the paywall must restore capture", 0, secureFlagOf(activity))
    }

    @Test
    fun `a null window is a no-op`() {
        val window: Window? = null
        window.setScreenshotProtected(true)
        window.setScreenshotProtected(false)
    }
}
