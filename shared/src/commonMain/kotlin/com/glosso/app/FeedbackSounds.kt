package com.glosso.app

import com.glosso.app.platform.PlatformContext
import com.glosso.engine.phonetics.PhoneticLoop

/**
 * The feedback cues. Each one ships as `feedback_<name>.wav` in
 * shared/src/androidMain/res/raw, written by tools/sounds/make_feedback_sounds.py.
 */
enum class FeedbackCue {
    /** A correct answer. The two steps up play on a run of correct answers. */
    CORRECT,
    CORRECT_2,
    CORRECT_3,
    WRONG,
    /** An exercise set or a whole lesson is done. */
    COMPLETE,
    /** A pronunciation take scored perfect. */
    PERFECT,
    /** An achievement unlocked or a sound mastered (the celebration overlay). */
    ACHIEVEMENT,
    /** The daily streak grew by a day. */
    STREAK,
    ;

    val file: String get() = "feedback_" + name.lowercase()
}

/** The cues, kept decoded so a repeat costs almost nothing. */
interface FeedbackSoundPlayer {
    fun play(cue: FeedbackCue)
    fun release()
}

/** This platform's cues: SoundPool over `res/raw` on Android, `AVAudioPlayer` on iOS, `<audio>` on the web. */
expect fun feedbackSoundPlayer(context: PlatformContext): FeedbackSoundPlayer

/** Plays the cues. Loaded once per process; silent until [init]. */
object FeedbackSounds {

    private var player: FeedbackSoundPlayer? = null

    fun init(context: PlatformContext) {
        if (player != null) return
        player = feedbackSoundPlayer(context)
    }

    fun play(cue: FeedbackCue) {
        player?.play(cue)
    }

    /** [combo] is the run of correct answers including this one. */
    fun answer(correct: Boolean, combo: Int) = play(answerCue(correct, combo))

    fun answerCue(correct: Boolean, combo: Int): FeedbackCue = when {
        !correct -> FeedbackCue.WRONG
        combo >= 5 -> FeedbackCue.CORRECT_3
        combo >= 3 -> FeedbackCue.CORRECT_2
        else -> FeedbackCue.CORRECT
    }

    /**
     * The one cue for a scored take, biggest moment first. A celebration plays its
     * own cue from the overlay, so the take stays silent then.
     */
    fun takeCue(celebrated: Boolean, streakGrew: Boolean, score: Int): FeedbackCue? = when {
        celebrated -> null
        streakGrew -> FeedbackCue.STREAK
        score >= PhoneticLoop.PERFECT_SCORE -> FeedbackCue.PERFECT
        else -> null
    }

    fun release() {
        player?.release()
        player = null
    }
}
