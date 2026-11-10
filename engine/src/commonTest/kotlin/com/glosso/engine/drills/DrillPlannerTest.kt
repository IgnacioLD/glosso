package com.glosso.engine.drills

import com.glosso.engine.model.DrillBank
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.PhonemeErrorKindModel
import com.glosso.engine.model.PhonemeErrorModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrillPlannerTest {

    private fun drill(id: String, target: String, theme: String, contrast: String? = "s") = DrillModel(
        id = id,
        theme = theme,
        level = "A1",
        target = target,
        contrast = contrast,
        focus = listOf("think", "sink"),
        text = "I think my sister is thirsty.",
    )

    private val bank = DrillBank(
        drills = listOf(
            drill("th.family", "θ", "family"),
            drill("th.food", "θ", "food"),
            drill("th.travel", "θ", "travel", contrast = "t"),
            drill("ae.family", "æ", "family"),
        ),
    )

    /** θ is badly wrong (th→s), while æ is perfect and must not be suggested. */
    private val profile = PhoneticProfileModel(
        stats = listOf(
            PhonemeStatModel("θ", attempts = 5, correct = 0, errors = mapOf("s" to 5)),
            PhonemeStatModel("æ", attempts = 5, correct = 5),
        ),
    )

    @Test
    fun `the weakest sound drives the plan`() {
        val plan = DrillPlanner.plan(profile, bank)
        assertEquals("θ", plan.drills.first().target)
        assertTrue(plan.drills.all { it.target == "θ" })
    }

    @Test
    fun `the learner's actual confusion is preferred`() {
        val plan = DrillPlanner.plan(profile, bank, limit = 1)
        assertEquals("s", plan.drills.first().contrast)
    }

    @Test
    fun `a chosen theme filters the bank`() {
        val plan = DrillPlanner.plan(profile, bank, themes = setOf("food"))
        assertEquals(listOf("th.food"), plan.drills.map { it.id })
    }

    @Test
    fun `recently seen drills are skipped`() {
        val plan = DrillPlanner.plan(profile, bank, limit = 1, seenIds = setOf("th.family"))
        assertTrue(plan.drills.none { it.id == "th.family" })
    }

    @Test
    fun `suggested themes only cover the weak sounds`() {
        val themes = DrillPlanner.suggestedThemes(profile, bank).map { it.id }
        assertTrue(themes.contains("family") && themes.contains("food") && themes.contains("travel"))
    }

    @Test
    fun `an error picks the drill with the matching contrast`() {
        val error = PhonemeErrorModel(
            phoneme = "θ",
            actual = "s",
            kind = PhonemeErrorKindModel.SUBSTITUTION,
            ruleId = "en.th.stopping",
        )

        val pick = DrillPlanner.drillForError(error, bank.drills)
        assertEquals("th.family", pick?.id)
    }

    @Test
    fun `an error with no matching sound has no drill`() {
        val error = PhonemeErrorModel(
            phoneme = "ʒ",
            actual = "ʃ",
            kind = PhonemeErrorKindModel.SUBSTITUTION,
            ruleId = "en.zh.sh",
        )

        assertNull(DrillPlanner.drillForError(error, bank.drills))
    }
}
