package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.engine.phonetics.PhoneticLoop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Plays the cues. Loaded once per process; silent until [init] and while the Settings switch is off. */
object FeedbackSounds {

    private const val FILE = "glosso_sounds"
    private const val KEY_ENABLED = "enabled"

    private var player: FeedbackSoundPlayer? = null
    private var prefs: KeyValueStore? = null

    private val _enabled = MutableStateFlow(true)

    /** The Settings "Sounds" switch, on by default. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: PlatformContext, makePlayer: () -> FeedbackSoundPlayer = { feedbackSoundPlayer(context) }) {
        loadPrefs(context)
        if (player == null) player = makePlayer()
    }

    fun setEnabled(context: PlatformContext, value: Boolean) {
        loadPrefs(context)
        _enabled.value = value
        prefs?.edit { putBoolean(KEY_ENABLED, value) }
    }

    private fun loadPrefs(context: PlatformContext) {
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _enabled.value = loaded.getBoolean(KEY_ENABLED, true)
    }

    fun play(cue: FeedbackCue) {
        if (_enabled.value) player?.play(cue)
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
