package com.glosso.app

import com.glosso.app.audio.ClipPlayback
import com.glosso.app.audio.PlaybackQueue
import com.glosso.app.audio.SLOW_RATE
import com.glosso.app.audio.accentOf
import com.glosso.app.audio.clipFor
import com.glosso.app.audio.playbackFor
import com.glosso.app.audio.slowClipFor
import com.glosso.app.audio.voiceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceClipsTest {

    // A dialogue line of a male character: only male voices read it.
    private val maleLine = mapOf("en-GB-male" to "gb-m.ogg", "en-US-male" to "us-m.ogg")

    /** A drill rendered in both voices of both accents, as English content is today. */
    private fun drill(id: String) = mapOf(
        "en-GB-female" to "audio/$id/gb-f.ogg", "en-GB-female:slow" to "audio/$id/gb-f-slow.ogg",
        "en-GB-male" to "audio/$id/gb-m.ogg",
        "en-US-female" to "audio/$id/us-f.ogg", "en-US-female:slow" to "audio/$id/us-f-slow.ogg",
        "en-US-male" to "audio/$id/us-m.ogg", "en-US-male:slow" to "audio/$id/us-m-slow.ogg",
    )

    @Test
    fun `a line read by one voice plays that voice in the learner's accent, not the first clip`() {
        assertEquals("us-m.ogg", maleLine.clipFor("en-US"))
        assertEquals("gb-m.ogg", maleLine.clipFor("en-GB"))
        assertEquals("en-US", accentOf("en-US-female:slow"))
    }

    @Test
    fun `an accent the item was never read in falls back to what there is`() {
        assertEquals("gb-m.ogg", mapOf("en-GB-male" to "gb-m.ogg").clipFor("en-US"))
        assertNull(emptyMap<String, String>().clipFor("en-US"))
        // A slow clip is never the normal-speed reference.
        assertNull(mapOf("en-US-male:slow" to "x.ogg").clipFor("en-US"))
    }

    @Test
    fun `an item in several voices always plays the same one, and a course mixes them`() {
        val items = (1..40).map { drill("dr$it") }
        val picks = items.map { it.voiceFor("en-US") }
        // Stable: the same item asked again, or rebuilt in another order, gets the same voice.
        items.forEachIndexed { i, item ->
            assertEquals(picks[i], item.voiceFor("en-US"))
            assertEquals(picks[i], item.entries.reversed().associate { it.toPair() }.voiceFor("en-US"))
            assertEquals(item[picks[i]], item.clipFor("en-US"))
        }
        // Always in the learner's accent, and both voices are heard across the course.
        assertTrue(picks.all { it != null && accentOf(it) == "en-US" })
        assertEquals(setOf("en-US-female", "en-US-male"), picks.toSet())
        // The recorder's view of a line (filtered to the accent) picks the same voice.
        items.forEachIndexed { i, item ->
            assertEquals(picks[i], item.filterKeys { accentOf(it) == "en-US" }.voiceFor("en-US"))
        }
    }

    @Test
    fun `slow follows the item's own voice and otherwise slows its normal clip`() {
        val items = (1..40).map { drill("dr$it") }
        items.forEach { item ->
            val voice = item.voiceFor("en-GB")!!
            val slow = item["$voice:slow"]
            assertEquals(slow, item.slowClipFor("en-GB"))
            val expected = slow?.let { ClipPlayback(it, 1f) } ?: ClipPlayback(item.getValue(voice), SLOW_RATE)
            assertEquals(expected, item.playbackFor("en-GB", slow = true))
            assertEquals(ClipPlayback(item.getValue(voice), 1f), item.playbackFor("en-GB", slow = false))
        }
        // No slow clip in this accent: the normal one, never the other accent's slow clip.
        assertEquals("us-m.ogg", maleLine.clipFor("en-US", slow = true))
        assertNull(maleLine.slowClipFor("en-US"))
    }

    @Test
    fun `a dialogue plays every line in its speaker's voice in the learner's accent`() {
        val femaleLine = mapOf("en-GB-female" to "gb-f.ogg", "en-US-female" to "us-f.ogg")
        val queue = PlaybackQueue { "en-US" }
        queue.setPiece(listOf(femaleLine, maleLine, femaleLine))

        assertEquals(listOf("us-f.ogg", "us-m.ogg", "us-f.ogg"), (0..2).map { queue.pathFor(it) })
    }

    @Test
    fun `a slow listen without a rendered slow clip plays the normal clip at the slow rate`() {
        assertEquals(ClipPlayback("us-m.ogg", SLOW_RATE), maleLine.playbackFor("en-US", slow = true))
        assertEquals(ClipPlayback("us-m.ogg", 1f), maleLine.playbackFor("en-US", slow = false))
        assertNull(emptyMap<String, String>().playbackFor("en-US", slow = true))
    }
}
