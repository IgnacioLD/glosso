package com.glosso.app.audio

/**
 * Who owns a shared player right now. Pure, so the takeover rules are covered by
 * a unit test: the player grants the turn to each request and reads back the
 * owner that must be told it was superseded.
 *
 * Owners are compared by identity; `null` is the anonymous single-clip playback
 * (a drill, a word, a take) that owns no visible state.
 */
class PlaybackTurn {

    private var current: Any? = null

    /** Takes the turn for [owner]; returns the owner that lost it, if any. */
    fun take(owner: Any?): Any? {
        val previous = current
        current = owner
        return if (previous != null && previous !== owner) previous else null
    }

    /** Gives up the turn as [owner]; returns the owner to notify, when another held it. */
    fun release(owner: Any?): Any? {
        val previous = current ?: return null
        current = null
        return previous.takeIf { it !== owner }
    }

    /** The clip ended on its own: the turn is free, nobody was superseded. */
    fun finish() {
        current = null
    }
}
