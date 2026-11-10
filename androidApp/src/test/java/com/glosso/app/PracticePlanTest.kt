package com.glosso.app

import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PracticePlanTest {

    private val profile =
        PhoneticProfileModel(stats = listOf(PhonemeStatModel("θ", attempts = 5, correct = 1)))
    private val weakDrill = DrillModel(id = "d1", target = "θ", text = "think")
    private val general = listOf(DrillModel(id = "g1", target = "s", text = "sun"))

    @Test
    fun `no profile falls back to the general bank`() = runBlocking {
        val (drills, source) = planPractice(
            profile = null,
            plan = { error("must not plan without a profile") },
            fallback = { general },
        )

        assertEquals(general, drills)
        assertEquals(PracticeSource.GENERAL, source)
    }

    @Test
    fun `an empty plan falls back and is not called personalized`() = runBlocking {
        val (drills, source) = planPractice(
            profile = profile,
            plan = { emptyList() },
            fallback = { general },
        )

        assertEquals(general, drills)
        assertEquals(PracticeSource.NO_PLAN, source)
    }

    @Test
    fun `a failing plan falls back`() = runBlocking {
        val (drills, source) = planPractice(
            profile = profile,
            plan = { throw IllegalStateException("drills endpoint down") },
            fallback = { general },
        )

        assertEquals(general, drills)
        assertEquals(PracticeSource.NO_PLAN, source)
    }

    @Test
    fun `a real plan is used and marked personal`() = runBlocking {
        val (drills, source) = planPractice(
            profile = profile,
            plan = { listOf(weakDrill) },
            fallback = { general },
        )

        assertEquals(listOf(weakDrill), drills)
        assertEquals(PracticeSource.PROFILE, source)
    }
}
