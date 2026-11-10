package com.glosso.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {

    private val voice = "en-US-female"

    private fun clip(name: String) = mapOf(voice to name)
    private fun slowClip(normal: String, slow: String) = mapOf(voice to normal, "$voice:slow" to slow)
    private val silent = emptyMap<String, String>()

    private fun queue(vararg paragraphs: Map<String, String>) =
        PlaybackQueue { accentOf(voice) }.apply { setPiece(paragraphs.toList()) }

    private fun played(action: PlaybackAction): String =
        (action as PlaybackAction.Play).path

    @Test
    fun `paragraphs without audio are skipped in the queue`() {
        val q = queue(silent, clip("p1"), silent, clip("p3"))

        assertEquals(listOf(1, 3), q.state.queue)
        assertTrue(q.state.hasAudio)
    }

    @Test
    fun `a piece with no audio stays empty and plays nothing`() {
        val q = queue(silent, silent)

        assertFalse(q.state.hasAudio)
        assertEquals(PieceStatus.IDLE, q.state.status)
        assertEquals(PlaybackAction.None, q.play(0))
    }

    @Test
    fun `playing starts at the tapped paragraph`() {
        val q = queue(clip("p0"), clip("p1"), clip("p2"))

        val action = q.play(2)

        assertEquals("p2", played(action))
        assertEquals(2, q.state.currentParagraph)
        assertEquals(PieceStatus.LOADING, q.state.status)
    }

    @Test
    fun `tapping a paragraph without audio starts at the next one that has it`() {
        val q = queue(clip("p0"), silent, clip("p2"))

        val action = q.play(1)

        assertEquals("p2", played(action))
        assertEquals(2, q.state.currentParagraph)
    }

    @Test
    fun `segments chain one after another`() {
        val q = queue(clip("p0"), clip("p1"), clip("p2"))

        assertEquals("p0", played(q.play(0)))
        assertEquals(PlaybackAction.None, q.onPrepared())
        assertEquals(PieceStatus.PLAYING, q.state.status)

        assertEquals("p1", played(q.onCompleted()))
        assertEquals(1, q.state.currentParagraph)
        assertEquals("p2", played(q.onCompleted()))
        assertEquals(2, q.state.currentParagraph)
    }

    @Test
    fun `the piece finishes after the last segment`() {
        val q = queue(clip("p0"), clip("p1"))

        q.play(0)
        q.onPrepared()
        q.onCompleted()

        assertEquals(PlaybackAction.None, q.onCompleted())
        assertEquals(PieceStatus.IDLE, q.state.status)
        assertEquals(-1, q.state.cursor)
        assertTrue(q.state.finished)
        assertNull(q.state.currentParagraph)
    }

    @Test
    fun `playing again clears the finished state`() {
        val q = queue(clip("p0"))
        q.play(0)
        q.onPrepared()
        q.onCompleted()
        assertTrue(q.state.finished)

        q.play(0)

        assertFalse(q.state.finished)
    }

    @Test
    fun `a new silent piece clears the old slow choice`() {
        val q = queue(slowClip("p0", "p0.slow"))
        q.setSlow(true)
        assertTrue(q.state.slow)

        q.setPiece(listOf(silent))

        assertFalse(q.state.slow)
        assertFalse(q.state.hasSlow)
    }

    @Test
    fun `pausing and resuming keeps the position`() {
        val q = queue(clip("p0"), clip("p1"), clip("p2"))
        q.play(1)
        q.onPrepared()

        assertEquals(PlaybackAction.Pause, q.pause())
        assertEquals(PieceStatus.PAUSED, q.state.status)
        assertEquals(1, q.state.currentParagraph)

        assertEquals(PlaybackAction.Resume, q.resume())
        assertEquals(PieceStatus.PLAYING, q.state.status)
        assertEquals(1, q.state.currentParagraph)
    }

    @Test
    fun `the big button toggles pause and resume`() {
        val q = queue(clip("p0"))
        q.play(0)
        q.onPrepared()

        assertEquals(PlaybackAction.Pause, q.toggle())
        assertEquals(PieceStatus.PAUSED, q.state.status)
        assertEquals(PlaybackAction.Resume, q.toggle())
        assertEquals(PieceStatus.PLAYING, q.state.status)
    }

    @Test
    fun `the big button starts from the beginning when idle`() {
        val q = queue(clip("p0"), clip("p1"))

        assertEquals("p0", played(q.toggle()))
        assertEquals(0, q.state.currentParagraph)
    }

    @Test
    fun `a failed segment is skipped rather than stuck`() {
        val q = queue(clip("p0"), clip("p1"))

        q.play(0)
        q.onPrepared()

        assertEquals("p1", played(q.onError()))
        assertEquals(1, q.state.currentParagraph)
    }

    @Test
    fun `slow is offered by rate when no slower clip exists`() {
        val q = queue(clip("p0"))

        assertTrue(q.state.hasSlow)

        q.play(0)
        q.onPrepared()
        val action = q.setSlow(true) as PlaybackAction.Play

        assertEquals("p0", action.path)
        assertEquals(SLOW_RATE, action.rate, 0.001f)
        assertTrue(q.state.slow)
        assertEquals(SLOW_RATE, q.state.rate, 0.001f)
    }

    @Test
    fun `slowing back to normal restores the normal rate`() {
        val q = queue(clip("p0"))
        q.play(0)
        q.onPrepared()
        q.setSlow(true)

        val action = q.setSlow(false) as PlaybackAction.Play

        assertEquals(1f, action.rate, 0.001f)
        assertFalse(q.state.slow)
    }

    @Test
    fun `slow restarts the current segment with the slow clip`() {
        val q = queue(slowClip("p0", "p0.slow"), clip("p1"))

        assertTrue(q.state.hasSlow)
        q.play(0)
        q.onPrepared()

        val action = q.setSlow(true)

        assertEquals("p0.slow", played(action))
        assertEquals(0, q.state.currentParagraph)
        assertEquals(PieceStatus.LOADING, q.state.status)
    }

    @Test
    fun `changing speed while idle only stores the preference`() {
        val q = queue(slowClip("p0", "p0.slow"))

        assertEquals(PlaybackAction.None, q.setSlow(true))
        assertTrue(q.state.slow)
        assertEquals("p0.slow", q.pathFor(0))
    }

    @Test
    fun `slow falls back to the normal clip when the slow one is missing`() {
        val q = queue(clip("p0"))

        assertEquals("p0", q.pathFor(0, slow = true))
    }

    @Test
    fun `the next path is offered for preloading`() {
        val q = queue(clip("p0"), clip("p1"))

        q.play(0)

        assertEquals("p1", q.nextPath())
    }
}
