package com.glosso.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The device identity persists in `glosso_device_id` and survives a second
 * launch; it is a plain UUID and never derived from hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceIdTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clear() {
        app.getSharedPreferences("glosso_device", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `creates the id once and persists it`() {
        DeviceId.init(app)
        val first = DeviceId.valueOrNull()
        assertNotNull(first)
        assertTrue("a UUID-shaped id", UUID_PATTERN.matches(first!!))

        val persisted = app.getSharedPreferences("glosso_device", Context.MODE_PRIVATE)
            .getString("glosso_device_id", null)
        assertEquals(first, persisted)

        DeviceId.init(app)
        assertEquals("idempotent", first, DeviceId.valueOrNull())
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
