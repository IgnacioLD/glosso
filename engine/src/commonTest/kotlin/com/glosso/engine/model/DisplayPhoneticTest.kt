package com.glosso.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The displayed phonetic is the textbook `displayIpa` when the content ships one,
 * and the scored `ipa` otherwise. The app shows this, never `ipa` directly, so the
 * picture and the score are allowed to be two different representations.
 */
class DisplayPhoneticTest {

    @Test
    fun `falls back to the reference when no textbook form ships`() {
        val drill = DrillModel(id = "d", target = "θ", text = "think", ipa = "θˈɪŋk")
        assertEquals("θˈɪŋk", drill.displayPhonetic)

        val word = WordModel(id = "w", lemma = "think", ipa = "θˈɪŋk")
        assertEquals("θˈɪŋk", word.displayPhonetic)
    }

    @Test
    fun `prefers the textbook form when present`() {
        val drill = DrillModel(
            id = "d", target = "θ", text = "think",
            ipa = "θˈɪŋk", displayIpa = "θˈɪŋk",
        )
        assertEquals("θˈɪŋk", drill.displayPhonetic)

        val word = WordModel(
            id = "w", lemma = "near", ipa = "nˈɪɹ", displayIpa = "nˈɪə",
        )
        assertEquals("nˈɪə", word.displayPhonetic)
    }

    @Test
    fun `dialogue turns and stories follow the same rule`() {
        val turn = DialogueTurn(speaker = "A", text = "near", ipa = "nˈɪɹ", displayIpa = "nˈɪə")
        assertEquals("nˈɪə", turn.displayPhonetic)

        val story = StoryModel(id = "s", title = "t", ipa = "nˈɪɹ", displayIpa = "nˈɪə")
        assertEquals("nˈɪə", story.displayPhonetic)
    }
}
