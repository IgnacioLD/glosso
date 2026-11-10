package com.glosso.app.audio

/**
 * The state of one continuous piece (a story or article read paragraph by
 * paragraph). Pure data, so it can be driven and asserted from a unit test.
 */
enum class PieceStatus {
    /** Nothing loaded or the piece finished. */
    IDLE,

    /** The current segment is being prepared; the UI shows a spinner. */
    LOADING,

    /** A segment is playing. */
    PLAYING,

    /** The learner paused mid-piece; resuming picks up where it stopped. */
    PAUSED,
}

data class PieceState(
    val status: PieceStatus = PieceStatus.IDLE,
    /** Paragraph indices that carry audio, in reading order. Gaps are skipped. */
    val queue: List<Int> = emptyList(),
    /** Position inside [queue], not a paragraph index. */
    val cursor: Int = -1,
    val slow: Boolean = false,
    /**
     * True when a slower listen is available: a rendered slow clip, or (for a
     * piece whose clips were only rendered at normal speed, like a story) the
     * normal clip played at [SLOW_RATE].
     */
    val hasSlow: Boolean = false,
    /** Playback rate of the current segment: [SLOW_RATE] when slowing by rate. */
    val rate: Float = 1f,
    /** True after the last segment ended, so the UI can say so instead of faking a start. */
    val finished: Boolean = false,
) {
    val currentParagraph: Int? get() = queue.getOrNull(cursor)
    val hasAudio: Boolean get() = queue.isNotEmpty()
    val position: Int get() = if (cursor < 0) 0 else cursor + 1
    val total: Int get() = queue.size
    val isActive: Boolean get() = status != PieceStatus.IDLE
    val isPlaying: Boolean get() = status == PieceStatus.PLAYING
    val isPaused: Boolean get() = status == PieceStatus.PAUSED
    val isLoading: Boolean get() = status == PieceStatus.LOADING
}

/** What the player should do next; the queue itself knows no Android. */
sealed interface PlaybackAction {
    data class Play(val path: String, val rate: Float = 1f) : PlaybackAction
    data object Pause : PlaybackAction
    data object Resume : PlaybackAction
    data object Stop : PlaybackAction
    data object None : PlaybackAction
}

/** How much slower a piece is read when it has no rendered slow clip. */
const val SLOW_RATE = 0.8f

/**
 * Decides which paragraph plays now, what comes next, and how pause, resume and
 * the slow variant map onto the clip list. It is the single source of truth for
 * a piece's playback; a thin Android layer turns the [PlaybackAction] it returns
 * into sound. The accent is read lazily so a settings change is honoured without
 * rebuilding the queue.
 */
class PlaybackQueue(private val accent: () -> String) {

    private var audio: List<Map<String, String>> = emptyList()

    var state: PieceState = PieceState()
        private set

    /** Points the queue at a new piece. Always starts idle. */
    fun setPiece(paragraphs: List<Map<String, String>>, slow: Boolean = state.slow) {
        audio = paragraphs
        // A rendered slow clip is preferred, but a piece with any audio can always
        // be slowed by rate, so slow is offered whenever there is something to hear.
        val hasSlow = queueFor(slow = false).isNotEmpty()
        state = PieceState(
            status = PieceStatus.IDLE,
            queue = queueFor(slow),
            cursor = -1,
            // A new silent piece does not keep the old choice.
            slow = slow && hasSlow,
            hasSlow = hasSlow,
        )
    }

    /** The playable asset path for a paragraph, honouring the slow variant. */
    fun pathFor(paragraph: Int, slow: Boolean = state.slow): String? {
        val variants = audio.getOrNull(paragraph) ?: return null
        if (variants.isEmpty()) return null
        return variants.clipFor(accent(), slow)
    }

    /** Starts the piece at the first segment at or after [fromParagraph]. */
    fun play(fromParagraph: Int): PlaybackAction {
        val queue = queueFor(state.slow)
        if (queue.isEmpty()) {
            state = state.copy(status = PieceStatus.IDLE, queue = queue, cursor = -1, rate = 1f, finished = false)
            return PlaybackAction.None
        }
        val pos = queue.indexOfFirst { it >= fromParagraph }.takeIf { it >= 0 } ?: 0
        val rate = rateFor(queue[pos], state.slow)
        state = state.copy(status = PieceStatus.LOADING, queue = queue, cursor = pos, rate = rate, finished = false)
        return PlaybackAction.Play(pathFor(queue[pos])!!, rate)
    }

    /** The one big button: play, pause, resume, or cancel a pending load. */
    fun toggle(fromParagraph: Int = 0): PlaybackAction = when (state.status) {
        PieceStatus.PLAYING -> pause()
        PieceStatus.PAUSED -> resume()
        PieceStatus.LOADING -> stop()
        PieceStatus.IDLE -> play(fromParagraph)
    }

    fun pause(): PlaybackAction {
        if (state.status != PieceStatus.PLAYING) return PlaybackAction.None
        state = state.copy(status = PieceStatus.PAUSED)
        return PlaybackAction.Pause
    }

    fun resume(): PlaybackAction {
        if (state.status != PieceStatus.PAUSED) return PlaybackAction.None
        state = state.copy(status = PieceStatus.PLAYING)
        return PlaybackAction.Resume
    }

    /** The player reported the current segment is ready and now sounding. */
    fun onPrepared(): PlaybackAction {
        if (state.status == PieceStatus.LOADING) state = state.copy(status = PieceStatus.PLAYING)
        return PlaybackAction.None
    }

    /** A segment ended (or failed): move to the next, or finish the piece. */
    fun onCompleted(): PlaybackAction = advance()

    fun onError(): PlaybackAction = advance()

    fun setSlow(slow: Boolean): PlaybackAction {
        if (slow == state.slow || !state.hasSlow) return PlaybackAction.None
        state = state.copy(slow = slow)
        val current = state.currentParagraph
        if (state.isActive && current != null) {
            val rate = rateFor(current, slow)
            state = state.copy(status = PieceStatus.LOADING, rate = rate)
            return PlaybackAction.Play(pathFor(current)!!, rate)
        }
        return PlaybackAction.None
    }

    fun stop(): PlaybackAction {
        if (!state.isActive) return PlaybackAction.None
        state = state.copy(status = PieceStatus.IDLE, cursor = -1, rate = 1f, finished = false)
        return PlaybackAction.Stop
    }

    /** The path of the segment after the current one, for preloading. */
    fun nextPath(): String? {
        val next = state.queue.getOrNull(state.cursor + 1) ?: return null
        return pathFor(next)
    }

    private fun advance(): PlaybackAction {
        val next = state.cursor + 1
        if (next in state.queue.indices) {
            val rate = rateFor(state.queue[next], state.slow)
            state = state.copy(status = PieceStatus.LOADING, cursor = next, rate = rate)
            return PlaybackAction.Play(pathFor(state.queue[next])!!, rate)
        }
        state = state.copy(status = PieceStatus.IDLE, cursor = -1, rate = 1f, finished = true)
        return PlaybackAction.None
    }

    /**
     * The rate for a segment: a rendered `:slow` clip already carries the slower
     * tempo, so it plays at normal rate; a normal clip slowed by request plays at
     * [SLOW_RATE] so a story rendered only at speed is still listenable slowly.
     */
    private fun rateFor(paragraph: Int, slow: Boolean): Float {
        if (!slow) return 1f
        val variants = audio.getOrNull(paragraph) ?: return 1f
        return if (variants.slowClipFor(accent()) != null) 1f else SLOW_RATE
    }

    private fun queueFor(slow: Boolean): List<Int> =
        audio.indices.filter { pathFor(it, slow) != null }
}
