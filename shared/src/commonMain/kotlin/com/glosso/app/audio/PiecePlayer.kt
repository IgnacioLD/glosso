package com.glosso.app.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ties the pure [PlaybackQueue] to a shared [ClipPlayer]: it turns queue
 * decisions into sound, preloads the next segment and mirrors the queue state
 * for the UI.
 *
 * The player is arbitrated: the piece holds a token, and when another clip takes
 * the player the piece is told ([ClipPlayer.onTakenOver]) and drops to idle, so
 * its highlight, label and pause button never lie. A prepared notice from
 * someone else's clip is ignored for the same reason.
 */
class PiecePlayer(
    private val player: ClipPlayer,
    private val accent: () -> String,
    private val sourceOf: (path: String) -> AudioSource,
) {
    private val token = Any()
    private val queue = PlaybackQueue(accent)
    private val _state = MutableStateFlow(PieceState())
    val state: StateFlow<PieceState> = _state.asStateFlow()

    /** The learner started the piece (not a resume or the next segment); for usage statistics. */
    var onUserPlay: ((slow: Boolean) -> Unit)? = null

    /** A segment could not be played; for usage statistics. */
    var onPlaybackError: (() -> Unit)? = null

    init {
        player.onPrepared = { owner ->
            if (owner === token) {
                // Apply the segment's rate once it is prepared and before it
                // starts, so a slow listen (a rendered clip, or the normal clip at
                // a lower rate) is heard from the first syllable.
                player.setSpeed(queue.state.rate)
                if (queue.state.isLoading) {
                    queue.onPrepared()
                    publishState()
                }
            }
        }
        player.onTakenOver = { owner ->
            if (owner === token) loseTurn()
        }
    }

    /** Points the player at a new piece, stopping whatever was playing. */
    fun load(audio: List<Map<String, String>>) {
        player.stop(token)
        queue.setPiece(audio)
        publishState()
    }

    fun clear() = load(emptyList())

    /** The big play/pause control. Starts at the first paragraph when idle. */
    fun toggle() = userDispatch(queue.toggle())

    /** Tapping a paragraph plays from there (or the next one that has audio). */
    fun playFrom(paragraph: Int) = userDispatch(queue.play(paragraph))

    fun setSlow(slow: Boolean) = dispatch(queue.setSlow(slow))

    fun stop() = dispatch(queue.stop())

    /** The shared player moved to another clip: drop to idle without touching it. */
    private fun loseTurn() {
        if (!queue.state.isActive) return
        queue.stop()
        publishState()
    }

    private fun userDispatch(action: PlaybackAction) {
        if (action is PlaybackAction.Play) onUserPlay?.invoke(queue.state.slow)
        dispatch(action)
    }

    private fun dispatch(action: PlaybackAction) {
        publishState()
        when (action) {
            is PlaybackAction.Play -> {
                player.play(
                    source = sourceOf(action.path),
                    owner = token,
                    onDone = { dispatch(queue.onCompleted()) },
                    onError = {
                        onPlaybackError?.invoke()
                        dispatch(queue.onError())
                    },
                )
                queue.nextPath()?.let { player.preload(sourceOf(it)) }
            }
            PlaybackAction.Pause -> player.pause()
            PlaybackAction.Resume -> player.resume()
            PlaybackAction.Stop -> player.stop(token)
            PlaybackAction.None -> Unit
        }
    }

    private fun publishState() {
        _state.value = queue.state
    }
}
