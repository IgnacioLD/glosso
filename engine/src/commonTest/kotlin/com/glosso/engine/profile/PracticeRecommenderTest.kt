package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PracticeRecommenderTest {

    private fun profile(vararg stats: PhonemeStatModel) = PhoneticProfileModel(stats = stats.toList())

    private fun stat(phoneme: String, accuracy: Int, attempts: Int = 100) =
        PhonemeStatModel(phoneme, attempts = attempts, correct = accuracy * attempts / 100)

    @Test
    fun `the directive example becomes two sounds and a phrases row`() {
        val plan = PracticeRecommender.plan(
            profile = profile(stat("θ", 43), stat("ð", 51), stat("ɹ", 82)),
            history = emptyList(),
            today = 100,
        )
        assertEquals(listOf("θ", "ð"), plan.sounds)
        assertEquals(listOf(2, 2, 1), plan.items.map { it.minutes })
        assertEquals(PracticeKind.PHRASES, plan.items.last().kind)
        assertEquals(5, plan.totalMinutes)
    }

    @Test
    fun `a single weak sound takes the leftover minutes`() {
        val plan = PracticeRecommender.plan(
            profile = profile(stat("θ", 40), stat("ɹ", 100)),
            history = emptyList(),
            today = 100,
        )
        assertEquals(listOf(4, 1), plan.items.map { it.minutes })
        assertEquals(5, plan.totalMinutes)
    }

    @Test
    fun `no weak sounds means no plan`() {
        val plan = PracticeRecommender.plan(
            profile = profile(stat("θ", 100), stat("ɹ", 98, attempts = 1)),
            history = emptyList(),
            today = 100,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a persistent pattern is named as the reason`() {
        val history = (1..5L).map {
            PhonemeObservationModel("ð", it, total = 10, correct = 3, error = "d")
        }
        val plan = PracticeRecommender.plan(
            profile = profile(stat("ð", 30)),
            history = history,
            today = 5,
        )
        assertEquals(PracticeReason.PERSISTENT, plan.items.first { it.phoneme == "ð" }.reason)
    }

    @Test
    fun `a longer budget fits more sounds and phrases`() {
        val plan = PracticeRecommender.plan(
            profile = profile(stat("θ", 40), stat("ð", 45), stat("ɪ", 50), stat("ɹ", 60)),
            history = emptyList(),
            today = 100,
            budgetMinutes = 7,
        )
        assertEquals(7, plan.totalMinutes)
        assertEquals(PracticeKind.PHRASES, plan.items.last().kind)
        assertEquals(3, plan.sounds.size)
    }
}
