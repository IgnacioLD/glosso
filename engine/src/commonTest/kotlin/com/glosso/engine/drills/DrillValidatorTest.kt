package com.glosso.engine.drills

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.model.DrillModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrillValidatorTest {

    private val g2p = CmuDictG2P.fromLines(
        listOf(
            "THINK  TH IH1 NG K",
            "SINK  S IH1 NG K",
            "SISTER  S IH1 S T ER0",
            "THIRSTY  TH ER1 S T IY0",
            "MY  M AY1",
            "IS  IH1 Z",
            "IT  IH1 T",
        ),
    )

    private fun drill(
        text: String,
        target: String = "θ",
        contrast: String? = "s",
        focus: List<String> = listOf("think", "sink"),
    ) = DrillModel(
        id = "d1",
        theme = "family",
        level = "A1",
        target = target,
        contrast = contrast,
        focus = focus,
        text = text,
    )

    @Test
    fun `a real pair carried by a sentence passes`() {
        val result = DrillValidator.validate(drill("I think my sister is thirsty."), g2p)
        assertTrue(result.ok, "unexpected problems: ${result.problems}")
        assertEquals(2, result.targetHits)
    }

    @Test
    fun `a sentence that barely uses the sound fails`() {
        val result = DrillValidator.validate(drill("I think it is my sister."), g2p)
        assertEquals(1, result.targetHits)
        assertTrue(result.problems.any { it.code == "target.hits" })
    }

    @Test
    fun `focus words that are not a minimal pair fail`() {
        val result = DrillValidator.validate(
            drill("I think my sister is thirsty.", focus = listOf("think", "sister")),
            g2p,
        )
        assertTrue(result.problems.any { it.code == "focus.pair" }, "problems: ${result.problems}")
    }

    @Test
    fun `a focus word missing from the sentence fails`() {
        val result = DrillValidator.validate(
            drill("My sister is thirsty.", focus = listOf("think", "sink")),
            g2p,
        )
        assertTrue(result.problems.any { it.code == "focus.absent" })
    }

    @Test
    fun `a missing final punctuation fails`() {
        val result = DrillValidator.validate(drill("I think my sister is thirsty"), g2p)
        assertTrue(result.problems.any { it.code == "text.punct" })
    }
}
