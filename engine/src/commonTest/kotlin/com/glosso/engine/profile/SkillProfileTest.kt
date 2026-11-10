package com.glosso.engine.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillProfileTest {

    @Test
    fun `only dimensions with enough data are reported`() {
        val scores = SkillProfile.build(
            pronunciation = SkillScore(SkillDimension.PRONUNCIATION, score = 62, sample = 20),
            competencies = mapOf(
                SkillDimension.VOCABULARY to CompetenceSignal(correct = 8, total = 10),
                SkillDimension.GRAMMAR to CompetenceSignal(correct = 1, total = 2),
            ),
        )
        assertEquals(
            listOf(SkillDimension.PRONUNCIATION, SkillDimension.VOCABULARY),
            scores.map { it.dimension },
        )
        assertEquals(80, scores.first { it.dimension == SkillDimension.VOCABULARY }.score)
    }

    @Test
    fun `a thin pronunciation sample is not reported`() {
        val scores = SkillProfile.build(
            pronunciation = SkillScore(SkillDimension.PRONUNCIATION, score = 90, sample = 1),
        )
        assertTrue(scores.isEmpty())
    }

    @Test
    fun `exercise types map to the competence they exercise`() {
        assertEquals(SkillDimension.VOCABULARY, SkillProfile.dimensionForExerciseType("match"))
        assertEquals(SkillDimension.VOCABULARY, SkillProfile.dimensionForExerciseType("multiple-choice"))
        assertEquals(SkillDimension.GRAMMAR, SkillProfile.dimensionForExerciseType("fill-blank"))
        assertEquals(SkillDimension.GRAMMAR, SkillProfile.dimensionForExerciseType("order"))
        assertEquals(SkillDimension.COMPREHENSION, SkillProfile.dimensionForExerciseType("true-false"))
        assertEquals(null, SkillProfile.dimensionForExerciseType("something-new"))
    }
}
