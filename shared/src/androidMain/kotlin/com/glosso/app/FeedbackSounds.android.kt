package com.glosso.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.glosso.shared.R

/**
 * Attributes for the short feedback cues: routed to the media stream so they
 * follow the media volume and honour silent/vibrate only like other media, not
 * the notification stream. The content type stays sonification because these
 * are cues, not music.
 */
fun feedbackAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

actual fun feedbackSoundPlayer(context: Context): FeedbackSoundPlayer = SoundPoolCues(context)

/**
 * The cues bundled as raw resources and played through SoundPool, which keeps
 * them decoded and low-latency.
 */
private class SoundPoolCues(context: Context) : FeedbackSoundPlayer {

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(feedbackAttributes())
        .build()
    private val ready = mutableSetOf<Int>()

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) ready += sampleId
        }
    }

    private val ids = FeedbackCue.entries.associateWith { pool.load(context, it.resource, 1) }

    override fun play(cue: FeedbackCue) {
        val id = ids[cue] ?: return
        if (id in ready) pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    override fun release() {
        ready.clear()
        pool.release()
    }
}

/** Spelled out, not looked up by name, so resource shrinking keeps every file. */
private val FeedbackCue.resource: Int
    get() = when (this) {
        FeedbackCue.CORRECT -> R.raw.feedback_correct
        FeedbackCue.CORRECT_2 -> R.raw.feedback_correct_2
        FeedbackCue.CORRECT_3 -> R.raw.feedback_correct_3
        FeedbackCue.WRONG -> R.raw.feedback_wrong
        FeedbackCue.COMPLETE -> R.raw.feedback_complete
        FeedbackCue.PERFECT -> R.raw.feedback_perfect
        FeedbackCue.ACHIEVEMENT -> R.raw.feedback_achievement
        FeedbackCue.STREAK -> R.raw.feedback_streak
    }
