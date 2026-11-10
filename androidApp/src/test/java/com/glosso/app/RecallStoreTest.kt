package com.glosso.app

import com.glosso.engine.lesson.RecallCard
import com.glosso.engine.lesson.RecallVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class RecallStoreTest {

    private class MemoryPrefs : RecallPrefs {
        val values = HashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
    }

    private var day = 20_000L
    private val prefs = MemoryPrefs()
    private val store = RecallStore(prefs) { day }
    private val card = RecallCard(ref = "en.dialogue.x.001#t1", text = "I'm fine, thanks.", cues = mapOf("es" to "Estoy bien, gracias."))

    @Test
    fun `a recalled phrase is not due today, is due tomorrow, and then waits longer`() {
        store.record("en", card, RecallVerdict.RECALLED)
        assertEquals(emptyList<RecallCard>(), store.due("en", "es"))

        day += 1
        assertEquals(listOf(card.ref), store.due("en", "es").map { it.ref })

        // Said again from review: it keeps the rung it had and climbs to the three-day interval.
        store.record("en", card, RecallVerdict.RECALLED)
        day += 2
        assertEquals(emptyList<RecallCard>(), store.due("en", "es"))
        day += 1
        assertEquals(1, store.due("en", "es").size)
    }

    @Test
    fun `cards are kept per target language and asked only where they can be prompted`() {
        store.record("en", card, null)
        day += 1

        assertEquals(1, store.due("en", "es").size)
        assertEquals(0, store.due("en", "fr").size)
        assertEquals(0, store.due("ru", "es").size)
    }

    @Test
    fun `corrupt or unknown stored data reads as no cards`() {
        prefs.values["cards_en"] = "{not json"
        assertEquals(emptyList<RecallCard>(), store.cards("en"))
        prefs.values["cards_en"] = """{"version":99,"cards":[{"ref":"a","text":"b"}]}"""
        assertEquals(emptyList<RecallCard>(), store.cards("en"))
    }

    @Test
    fun `saying a phrase again before it is due does not climb the ladder`() {
        store.record("en", card, RecallVerdict.RECALLED)
        val again = store.record("en", card, RecallVerdict.RECALLED)

        assertEquals(1, again.step)
        assertEquals(day + 1, again.dueDay)
        // A miss before it is due still counts: it comes back tomorrow from the start.
        val missed = store.record("en", card, RecallVerdict.NOT_THAT_PHRASE)
        assertEquals(0 to 1, missed.step to missed.lapses)
    }

    @Test
    fun `a phrase recalled past the last interval leaves the store`() {
        repeat(6) {
            store.record("en", card, RecallVerdict.RECALLED)
            day += 40
        }

        assertEquals(emptyList<RecallCard>(), store.cards("en"))
    }
}
