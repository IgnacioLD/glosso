package com.glosso.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The takeover arbitration of the real [Player], not a double that reimplements
 * it: a piece is playing and another clip (a word, a drill, a take) asks for the
 * player, so the piece must be told and fall back to idle instead of staying
 * stuck in a stale playing state.
 */
class PlayerTest {

    private val voice = "en-US-female"

    private fun clip(name: String) = mapOf(voice to name)

    @Test
    fun `another clip taking the player drops the playing piece to idle`() {
        val devices = Devices()
        val player = Player(devices::create)
        val piece = PiecePlayer(player, { accentOf(voice) }) { path -> AudioSource.Url(path) }
        piece.load(listOf(clip("p0")))

        piece.toggle()
        devices.latest.firePrepared()
        assertTrue(piece.state.value.isPlaying)

        player.play(AudioSource.Url("a-word"), owner = null)

        assertEquals(PieceStatus.IDLE, piece.state.value.status)
        assertFalse(piece.state.value.isPlaying)
        assertFalse(piece.state.value.isActive)
    }

    @Test
    fun `taking the turn notifies exactly the previous owner`() {
        val devices = Devices()
        val player = Player(devices::create)
        val pieceOwner = Any()
        val lost = mutableListOf<Any>()
        player.onTakenOver = { lost += it }

        player.play(AudioSource.Url("p0"), owner = pieceOwner)
        player.play(AudioSource.Url("a-word"), owner = Any())

        assertEquals(listOf<Any>(pieceOwner), lost)
    }

    @Test
    fun `a prepared notice reaches the piece that asked for the clip`() {
        val devices = Devices()
        val player = Player(devices::create)
        val piece = PiecePlayer(player, { accentOf(voice) }) { path -> AudioSource.Url(path) }
        piece.load(listOf(clip("p0")))

        piece.toggle()
        assertTrue(piece.state.value.isLoading)

        devices.latest.firePrepared()

        assertEquals(PieceStatus.PLAYING, piece.state.value.status)
    }

    @Test
    fun `stopping the player as the owner does not report a takeover`() {
        val devices = Devices()
        val player = Player(devices::create)
        val owner = Any()
        val lost = mutableListOf<Any>()
        player.onTakenOver = { lost += it }

        player.play(AudioSource.Url("p0"), owner = owner)
        player.stop(owner)

        assertTrue(lost.isEmpty())
    }

    private class Devices {
        private val created = mutableListOf<FakeDevice>()
        val latest: FakeDevice get() = created.last()
        fun create(): AudioDevice = FakeDevice().also { created += it }
    }

    private class FakeDevice : AudioDevice {
        private var preparedBlock: (() -> Unit)? = null
        private var playing = false

        override fun setSource(source: AudioSource): Boolean = true
        override fun onPrepared(block: () -> Unit) { preparedBlock = block }
        override fun onCompletion(block: () -> Unit) {}
        override fun onError(block: () -> Unit) {}
        override fun prepareAsync(): Boolean = true
        override fun start() { playing = true }
        override fun pause() { playing = false }
        override fun stop() { playing = false }
        override fun release() {}
        override fun setSpeed(speed: Float) {}
        override val isPlaying: Boolean get() = playing

        fun firePrepared() { preparedBlock?.invoke() }
    }
}
