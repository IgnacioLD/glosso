package com.glosso.app.debugtools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs only for the debug variant (`:androidApp:test<Flavor>DebugUnitTest`): the model
 * comparison tool is compiled in and its entry point is reachable.
 */
class ModelCompareDebugTest {

    @Test
    fun `debug ships the model comparison tool`() {
        assertTrue(modelCompareAvailable())
    }
}
