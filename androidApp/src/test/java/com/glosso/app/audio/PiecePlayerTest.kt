package com.glosso.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring between the pure queue and the shared player: chaining with
 * preload, the takeover that used to leave the state lying, and the error path.
 */
class PiecePlayerTest {

    private val voice = "en-US-female"

    private fun clip(name: String) = mapOf(voice to name)

    private fun player() = FakeClipPlayer()

    private fun piece(player: FakeClipPlayer) =
        PiecePlayer(player, { accentOf(voice) }, { path -> AudioSource.Url(path) })

    @Test
    fun `starting the piece plays the first segment and preloads the next`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0"), clip("p1")))

        piece.toggle()

        assertEquals(listOf<AudioSource>(AudioSource.Url("p0")), player.played)
        assertEquals(listOf<AudioSource>(AudioSource.Url("p1")), player.preloaded)
        assertTrue(piece.state.value.isLoading)
    }

    @Test
    fun `a prepared notice from the piece moves it to playing`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0")))

        piece.toggle()
        player.firePrepared(player.lastOwner)

        assertTrue(piece.state.value.isPlaying)
    }

    @Test
    fun `another clip taking the player drops the piece to idle`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0")))
        piece.toggle()
        player.firePrepared(player.lastOwner)
        assertTrue(piece.state.value.isPlaying)

        player.playForeign(AudioSource.Url("a-word"))

        assertEquals(PieceStatus.IDLE, piece.state.value.status)
        assertFalse(piece.state.value.isPlaying)
        assertFalse(piece.state.value.isActive)
    }

    @Test
    fun `a foreign prepared notice does not move the loading piece`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0")))

        piece.toggle()
        player.firePrepared(Any())

        assertTrue(piece.state.value.isLoading)
        assertFalse(piece.state.value.isPlaying)
    }

    @Test
    fun `the next segment starts on completion`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0"), clip("p1")))
        piece.toggle()
        player.firePrepared(player.lastOwner)

        player.fireCompleted()

        assertEquals(AudioSource.Url("p1"), player.played.last())
    }

    @Test
    fun `an error skips to the next segment`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0"), clip("p1")))
        piece.toggle()

        player.fireError()

        assertEquals(AudioSource.Url("p1"), player.played.last())
    }

    @Test
    fun `the piece reports finished after the last segment`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0")))
        piece.toggle()
        player.firePrepared(player.lastOwner)

        player.fireCompleted()

        assertTrue(piece.state.value.finished)
        assertEquals(PieceStatus.IDLE, piece.state.value.status)
    }

    @Test
    fun `a piece without a slow clip slows the normal clip by rate`() {
        val player = player()
        val piece = piece(player)
        piece.load(listOf(clip("p0")))
        piece.setSlow(true)

        piece.toggle()
        player.firePrepared(player.lastOwner)

        assertEquals(SLOW_RATE, player.speedUsed)
        assertEquals(SLOW_RATE, piece.state.value.rate)
    }

    @Test
    fun `a rendered slow clip plays at normal rate`() {
        val player = player()
        val piece = piece(player)
        piece.load(
            listOf(mapOf(voice to "p0", "$voice:slow" to "p0.slow")),
        )
        piece.setSlow(true)

        piece.toggle()
        player.firePrepared(player.lastOwner)

        assertEquals(listOf<AudioSource>(AudioSource.Url("p0.slow")), player.played)
        assertEquals(1f, player.speedUsed)
    }
}

/**
 * A player double that mirrors the real arbitration through [PlaybackTurn], so
 * these tests exercise the takeover rules the app relies on.
 */
private class FakeClipPlayer : ClipPlayer {
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onPrepared: ((Any?) -> Unit)? = null
    override var onTakenOver: ((Any) -> Unit)? = null

    private val turn = PlaybackTurn()
    private var onDone: (() -> Unit)? = null
    private var onError: (() -> Unit)? = null

    val played = mutableListOf<AudioSource>()
    val preloaded = mutableListOf<AudioSource>()
    var speedUsed: Float? = null
        private set
    var lastOwner: Any? = null
        private set

    override fun play(source: AudioSource, owner: Any?, onDone: () -> Unit, onError: (() -> Unit)?, speed: Float) {
        turn.take(owner)?.let { onTakenOver?.invoke(it) }
        lastOwner = owner
        this.onDone = onDone
        this.onError = onError
        played += source
        onPlayingChanged?.invoke(true)
    }

    override fun preload(source: AudioSource) {
        preloaded += source
    }

    override fun setSpeed(speed: Float) {
        speedUsed = speed
    }

    override fun pause() {
        onPlayingChanged?.invoke(false)
    }

    override fun resume() {
        onPlayingChanged?.invoke(true)
    }

    override fun stop(owner: Any?) {
        turn.release(owner)?.let { onTakenOver?.invoke(it) }
        onPlayingChanged?.invoke(false)
    }

    /** A drill, a word or a take owning the shared player. */
    fun playForeign(source: AudioSource) = play(source, owner = null, onDone = {}, onError = null)

    fun firePrepared(owner: Any?) = onPrepared?.invoke(owner)

    fun fireCompleted() {
        turn.finish()
        onPlayingChanged?.invoke(false)
        onDone?.invoke()
    }

    fun fireError() {
        turn.finish()
        onPlayingChanged?.invoke(false)
        (onError ?: onDone)?.invoke()
    }
}
