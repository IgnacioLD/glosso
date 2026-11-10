package com.glosso.app

import com.glosso.app.platform.PlatformContext

/** The two short answer cues, kept decoded so a repeat answer costs almost nothing. */
interface FeedbackSoundPlayer {
    fun correct()
    fun wrong()
    fun release()
}

/** This platform's cues: SoundPool over `res/raw` on Android, `AVAudioPlayer` on iOS. */
expect fun feedbackSoundPlayer(context: PlatformContext): FeedbackSoundPlayer

/**
 * Two tiny synthesized cues: a warm rising chime for a correct answer and a soft
 * falling pair for a miss. Loaded once per process; silent until [init].
 */
object FeedbackSounds {

    private var player: FeedbackSoundPlayer? = null

    fun init(context: PlatformContext) {
        if (player != null) return
        player = feedbackSoundPlayer(context)
    }

    fun correct() {
        player?.correct()
    }

    fun wrong() {
        player?.wrong()
    }

    fun release() {
        player?.release()
        player = null
    }
}
