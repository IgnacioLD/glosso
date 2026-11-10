package com.glosso.app.audio

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackTurnTest {

    private val piece = Any()
    private val other = Any()

    @Test
    fun `the first take owns the turn without evicting anyone`() {
        val turn = PlaybackTurn()

        assertNull(turn.take(piece))
    }

    @Test
    fun `a different owner evicts the current one`() {
        val turn = PlaybackTurn()
        turn.take(piece)

        assertSame(piece, turn.take(other))
    }

    @Test
    fun `the same owner keeps the turn without an eviction`() {
        val turn = PlaybackTurn()
        turn.take(piece)

        assertNull(turn.take(piece))
    }

    @Test
    fun `an anonymous clip evicts the piece`() {
        val turn = PlaybackTurn()
        turn.take(piece)

        assertSame(piece, turn.take(null))
    }

    @Test
    fun `releasing by the owner announces nothing`() {
        val turn = PlaybackTurn()
        turn.take(piece)

        assertNull(turn.release(piece))
    }

    @Test
    fun `releasing by someone else announces the owner`() {
        val turn = PlaybackTurn()
        turn.take(piece)

        assertSame(piece, turn.release(null))
    }

    @Test
    fun `releasing an empty turn announces nothing`() {
        assertNull(PlaybackTurn().release(null))
    }

    @Test
    fun `finishing frees the turn for the next take`() {
        val turn = PlaybackTurn()
        turn.take(piece)
        turn.finish()

        assertNull(turn.take(other))
    }
}
