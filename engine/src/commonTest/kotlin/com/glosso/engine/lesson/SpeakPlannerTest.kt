package com.glosso.engine.lesson

import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DialogueTurn
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakPlannerTest {

    private fun turn(speaker: String, text: String, es: String? = "x", ipa: String = "a", audio: Boolean = true) = DialogueTurn(
        speaker = speaker,
        text = text,
        ipa = ipa,
        translations = es?.let { mapOf("es" to it) } ?: emptyMap(),
        audio = if (audio) mapOf("en-GB-female" to "audio/a.ogg") else emptyMap(),
    )

    private fun dialogue(vararg turns: DialogueTurn) = DialogueModel(id = "en.dialogue.x.001", title = "X", turns = turns.toList())

    @Test
    fun `the learner plays the character who answers and each line keeps the one that prompts it`() {
        val plan = SpeakPlanner.plan(
            dialogue(
                turn("Mum", "Hello, Ben! How are you?"),
                turn("Ben", "I'm fine, thanks."),
                turn("Mum", "Dinner is at six."),
                turn("Ben", "See you at six."),
            ),
            "es",
        )

        assertEquals(listOf(SpeakLine(1, 0), SpeakLine(3, 2)), plan)
    }

    @Test
    fun `a line that cannot be prompted - scored or heard is not asked for`() {
        val plan = SpeakPlanner.plan(
            dialogue(
                turn("A", "Good morning to you."),
                turn("B", "Yes."),
                turn("A", "Where do you live?"),
                turn("B", "I live near the old bridge.", es = null),
                turn("A", "Is it far from here?"),
                turn("B", "No, it is close.", ipa = ""),
                turn("A", "That is good news."),
                turn("B", "We can walk there.", audio = false),
            ),
            "es",
        )

        // B has nothing left to say, so the learner plays A; the first line has no partner before it.
        assertEquals(listOf(SpeakLine(0, null), SpeakLine(2, 1), SpeakLine(4, 3), SpeakLine(6, 5)), plan)
    }

    @Test
    fun `no more than four lines`() {
        val turns = (1..6).flatMap { listOf(turn("A", "What is number $it?"), turn("B", "It is number $it.")) }

        assertEquals(SpeakPlanner.MAX_LINES, SpeakPlanner.plan(dialogue(*turns.toTypedArray()), "es").size)
    }

    @Test
    fun `without a translation in the interface language the other character's line is the prompt`() {
        val plan = SpeakPlanner.plan(
            dialogue(
                turn("Mum", "Hello, Ben! How are you?"),
                turn("Ben", "I'm fine, thanks."),
                turn("Ben", "What is for dinner?"),
                turn("Mum", "Chicken and rice."),
                turn("Ben", "See you at six."),
            ),
            "en",
        )

        // Ben's second line follows his own, so nothing prompts it; the first line of the dialogue never is.
        assertEquals(listOf(SpeakLine(1, 0), SpeakLine(4, 3)), plan)
    }

    @Test
    fun `a dialogue translated for some lines only asks for those`() {
        val plan = SpeakPlanner.plan(
            dialogue(
                turn("A", "Where do you live?"),
                turn("B", "I live near the bridge.", es = null),
                turn("A", "Is it far from here?"),
                turn("B", "No, it is close."),
            ),
            "es",
        )

        assertEquals(listOf(SpeakLine(0, null), SpeakLine(2, 1)), plan)
    }

    @Test
    fun `a spaced dash does not count as a word`() {
        val plan = SpeakPlanner.plan(dialogue(turn("A", "Hi."), turn("B", "Kvas - good.")), "es")

        assertEquals(listOf(SpeakLine(1, 0)), plan)
    }
}
