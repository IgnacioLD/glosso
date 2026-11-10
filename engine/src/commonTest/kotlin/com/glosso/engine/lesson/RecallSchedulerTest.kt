package com.glosso.engine.lesson

import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DialogueTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class RecallSchedulerTest {

    private val card = RecallCard(ref = "en.dialogue.x.001#t1", text = "I'm fine, thanks.")
    private val today = 20_000L

    @Test
    fun `a recalled phrase comes back after a growing number of days`() {
        var c = card
        val gaps = RecallScheduler.LADDER.map {
            val next = RecallScheduler.review(c, RecallVerdict.RECALLED, today)
            c = next
            next.dueDay - today
        }

        assertEquals(listOf(1L, 3L, 7L, 16L, 35L), gaps)
        // Past the last rung it keeps the longest interval instead of running off the ladder.
        assertEquals(35L, RecallScheduler.review(c, RecallVerdict.RECALLED, today).dueDay - today)
    }

    @Test
    fun `a missed phrase starts again tomorrow and a partial one keeps its rung`() {
        val settled = card.copy(step = 3, dueDay = today)

        val missed = RecallScheduler.review(settled, RecallVerdict.NOT_THAT_PHRASE, today)
        assertEquals(Triple(0, today + 1, 1), Triple(missed.step, missed.dueDay, missed.lapses))
        // Shown without saying it counts as not recalled.
        assertEquals(missed, RecallScheduler.review(settled, null, today))

        val partial = RecallScheduler.review(settled, RecallVerdict.PARTIAL, today)
        assertEquals(Triple(3, today + 1, 0), Triple(partial.step, partial.dueDay, partial.lapses))
    }

    @Test
    fun `review asks the most overdue first - the most missed next - and never more than the limit`() {
        val cards = listOf(
            card.copy(ref = "a", dueDay = today + 1),
            card.copy(ref = "b", dueDay = today, lapses = 0),
            card.copy(ref = "c", dueDay = today, lapses = 2),
            card.copy(ref = "d", dueDay = today - 5),
            card.copy(ref = "e", dueDay = today - 1),
            card.copy(ref = "f", dueDay = today - 1),
        )

        assertEquals(listOf("d", "e", "f", "c"), RecallScheduler.due(cards, today).map { it.ref })
        assertEquals(emptyList(), RecallScheduler.due(cards, today - 10))
    }

    @Test
    fun `a reviewed card replaces the stored one and the others stay`() {
        val stored = listOf(card.copy(ref = "a"), card.copy(ref = "b", text = "old wording"))

        val merged = RecallScheduler.merge(stored, card.copy(ref = "b", text = "new wording", step = 2))

        assertEquals(listOf("a", "b"), merged.map { it.ref })
        assertEquals("new wording" to 2, merged.last().text to merged.last().step)
    }

    @Test
    fun `the lines of a dialogue become cards that carry their prompt and their partner line`() {
        val dialogue = DialogueModel(
            id = "en.dialogue.x.001",
            title = "X",
            turns = listOf(
                DialogueTurn("Mum", "How are you?", ipa = "a", translations = mapOf("es" to "¿Cómo estás?"), audio = mapOf("en-GB-female" to "m.ogg")),
                DialogueTurn("Ben", "I'm fine, thanks.", ipa = "b", translations = mapOf("es" to "Estoy bien, gracias."), audio = mapOf("en-GB-male" to "b.ogg")),
            ),
        )

        val cards = SpeakPlanner.cards(dialogue, "en.lesson.x.001", "es")

        // Ben answers, so the learner plays Ben; Mum's line has nobody before it.
        val ben = cards.single { it.speaker == "Ben" }
        assertEquals("en.dialogue.x.001#t1", ben.ref)
        assertEquals("Estoy bien, gracias.", ben.cues["es"])
        assertEquals("How are you?" to "Mum", ben.partnerText to ben.partnerSpeaker)
        assertEquals(mapOf("en-GB-female" to "m.ogg"), ben.partnerAudio)
        // A card survives storage as written.
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(ben, json.decodeFromString(RecallCard.serializer(), json.encodeToString(RecallCard.serializer(), ben)))
    }
}
