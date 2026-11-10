package com.glosso.app

import com.glosso.engine.model.DrillModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedStateTest {

    private fun drill(id: String) = DrillModel(id = id, target = "θ", text = id)

    @Test
    fun `an empty bank reads as finished, not as a stuck next phrase`() {
        // api.drills can return 0 items with no error; the step must not leave the
        // primary button asking for a phrase that does not exist.
        val state = SeedState(loading = false, drills = emptyList())

        assertTrue(state.finished)
        assertNull(state.drill)
    }

    @Test
    fun `a loading seed is never finished`() {
        assertFalse(SeedState(loading = true, drills = emptyList()).finished)
    }

    @Test
    fun `the seed finishes only after the last phrase`() {
        val drills = listOf(drill("a"), drill("b"))

        assertFalse(SeedState(loading = false, drills = drills, index = 0).finished)
        assertFalse(SeedState(loading = false, drills = drills, index = 1).finished)
        assertTrue(SeedState(loading = false, drills = drills, index = 2).finished)
    }
}
