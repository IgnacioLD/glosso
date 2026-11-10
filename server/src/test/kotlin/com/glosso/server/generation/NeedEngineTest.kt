package com.glosso.server.generation

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeedEngineTest {

    private val userId = UUID.randomUUID()

    private fun phoneme(
        phoneme: String,
        score: Int,
        attempts: Int = 5,
        confidence: Float = 0.8f,
        errorPattern: String? = null,
        replacement: String? = null,
        position: String? = null,
        trend: String? = null,
    ) = PhonemeProfile(
        userId = userId,
        language = "en",
        phoneme = phoneme,
        score = score,
        confidence = confidence,
        errorPattern = errorPattern,
        replacementPhoneme = replacement,
        position = position,
        attempts = attempts,
        successfulAttempts = attempts,
        trend = trend,
    )

    @Test
    fun `a weak measured sound becomes a need carrying its class`() {
        val profile = LearnerProfile(userId = userId, language = "en", cefr = "A1")

        val needs = NeedEngine.derive(
            profile,
            listOf(
                phoneme(
                    phoneme = "θ",
                    score = 43,
                    errorPattern = "en.th.stopping.t",
                    replacement = "t",
                    position = "initial",
                    trend = NeedEngine.TREND_DECLINING,
                ),
            ),
        )

        val need = needs.single()
        assertEquals(SKILL_PRONUNCIATION, need.skill)
        assertEquals("A1", need.cefr)
        assertEquals("θ", need.phoneme)
        assertEquals("en.th.stopping.t", need.errorPattern)
        assertEquals("t", need.replacementPhoneme)
        assertEquals("initial", need.position)
        assertTrue(need.priority > 0.5f, "a 43 with strong evidence should be urgent: ${need.priority}")
    }

    @Test
    fun `a sound without enough evidence is not a need`() {
        val needs = NeedEngine.derive(null, listOf(phoneme("θ", score = 20, attempts = 2)))
        assertTrue(needs.isEmpty())
    }

    @Test
    fun `a mastered sound is not a need`() {
        val needs = NeedEngine.derive(null, listOf(phoneme("θ", score = 90)))
        assertTrue(needs.isEmpty())
    }

    @Test
    fun `needs are sorted worst first`() {
        val needs = NeedEngine.derive(
            LearnerProfile(userId = userId, language = "en", cefr = "A2"),
            listOf(phoneme("s", score = 70), phoneme("θ", score = 30)),
        )
        assertEquals(listOf("θ", "s"), needs.map { it.phoneme })
    }

    @Test
    fun `a need without a known cefr is bucketed as unknown`() {
        val needs = NeedEngine.derive(
            LearnerProfile(userId = userId, language = "en", cefr = null),
            listOf(phoneme("θ", score = 40)),
        )
        assertEquals(UNKNOWN_CEFR, needs.single().needClassKey().cefr)
    }

    @Test
    fun `a declining sound outranks an improving one at the same score`() {
        val declining = NeedEngine.priorityOf(43, 0.8f, NeedEngine.TREND_DECLINING)
        val improving = NeedEngine.priorityOf(43, 0.8f, NeedEngine.TREND_IMPROVING)
        assertTrue(declining > improving, "declining=$declining improving=$improving")
    }

    @Test
    fun `priority stays within its bounds`() {
        assertEquals(0f, NeedEngine.priorityOf(100, 1f))
        assertTrue(NeedEngine.priorityOf(0, 1f, NeedEngine.TREND_DECLINING) <= 1f)
        assertTrue(NeedEngine.priorityOf(0, 0f) >= 0f)
    }
}
