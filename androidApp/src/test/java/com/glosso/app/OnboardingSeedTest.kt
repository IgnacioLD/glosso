package com.glosso.app

import com.glosso.engine.model.DrillModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSeedTest {

    private fun drill(
        id: String,
        target: String,
        text: String = id,
        theme: String = "",
        withAudio: Boolean = true,
    ) = DrillModel(
        id = id,
        target = target,
        text = text,
        theme = theme,
        audio = if (withAudio) mapOf("en-GB-female" to "audio/$id.ogg") else emptyMap(),
    )

    @Test
    fun `picks one phrase per distinct trained sound`() {
        val pool = listOf(
            drill("a", "ɪ"),
            drill("b", "ɪ"),
            drill("c", "θ"),
            drill("d", "θ"),
            drill("e", "s"),
            drill("f", "ɹ"),
        )

        val targets = OnboardingSeed.select(pool, limit = 4).map { it.target }

        assertEquals(listOf("ɪ", "θ", "s", "ɹ"), targets)
    }

    @Test
    fun `never asks for more phrases than the limit`() {
        val pool = (1..10).map { drill("d$it", "p$it") }

        assertEquals(3, OnboardingSeed.select(pool, limit = 3).size)
    }

    @Test
    fun `asks for every distinct sound when the pool has fewer than the limit`() {
        val pool = listOf(drill("a", "θ"), drill("b", "θ"), drill("c", "s"))

        assertEquals(2, OnboardingSeed.select(pool, limit = 6).size)
    }

    @Test
    fun `starts with the sound the bank trains most`() {
        val pool = listOf(drill("a", "θ")) +
            (1..5).map { drill("s$it", "s") }

        // s is represented five times, θ once, so s leads and θ follows.
        assertEquals(listOf("s", "θ"), OnboardingSeed.select(pool, limit = 2).map { it.target })
    }

    @Test
    fun `ignores drills the seed cannot use`() {
        val pool = listOf(
            drill("blank-target", ""),
            drill("blank-text", "θ", text = ""),
            drill("good", "s"),
        )

        assertEquals(listOf("good"), OnboardingSeed.select(pool, limit = 6).map { it.id })
    }

    @Test
    fun `prefers a phrase with reference audio over one without`() {
        val pool = listOf(
            drill("silent", "θ", withAudio = false),
            drill("voiced", "θ", text = "a much longer phrase than the silent one", withAudio = true),
        )

        assertEquals("voiced", OnboardingSeed.select(pool, limit = 1).single().id)
    }

    @Test
    fun `spreads the phrases across themes`() {
        // "ɪ" is the most represented sound, so it is picked first and claims a
        // theme. The "θ" phrase then avoids repeating that theme even though the
        // other θ drill is shorter.
        val pool = listOf(
            drill("i1", "ɪ", theme = "greetings"),
            drill("i2", "ɪ", theme = "greetings"),
            drill("t1", "θ", theme = "greetings", text = "short"),
            drill("t2", "θ", theme = "health", text = "a noticeably longer sentence"),
        )

        assertEquals("t2", OnboardingSeed.select(pool, limit = 2).last().id)
    }

    @Test
    fun `is deterministic`() {
        val pool = listOf(
            drill("a", "θ", theme = "x"),
            drill("b", "s", theme = "y"),
            drill("c", "ɪ", theme = "x"),
            drill("d", "θ", theme = "y"),
        )

        assertEquals(
            OnboardingSeed.select(pool, limit = 3).map { it.id },
            OnboardingSeed.select(pool, limit = 3).map { it.id },
        )
    }

    @Test
    fun `an empty pool or a zero limit yields no phrases`() {
        assertTrue(OnboardingSeed.select(emptyList(), limit = 6).isEmpty())
        assertTrue(OnboardingSeed.select(listOf(drill("a", "θ")), limit = 0).isEmpty())
    }

    @Test
    fun `a single-sound bank yields a single phrase`() {
        val pool = listOf(drill("a", "θ"), drill("b", "θ"), drill("c", "θ"))

        assertEquals(1, OnboardingSeed.select(pool, limit = 6).size)
        assertFalse(OnboardingSeed.select(pool, limit = 6).any { it.target != "θ" })
    }
}
