package com.glosso.app.debugtools

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Runs only for the release variant (`:androidApp:test<Flavor>ReleaseUnitTest`): pins the
 * guard that keeps the model comparison tool out of a release build. The debug
 * source set holds the comparator; in release [modelCompareAvailable] is a
 * constant false and the screen is an empty stub.
 */
class ModelCompareReleaseTest {

    @Test
    fun `release ships no model comparison tool`() {
        assertFalse(modelCompareAvailable())
    }
}
