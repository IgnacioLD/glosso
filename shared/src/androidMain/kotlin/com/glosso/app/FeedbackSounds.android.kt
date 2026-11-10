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

    private var pool: SoundPool? = null
    private var correctId = 0
    private var wrongId = 0
    private var correctReady = false
    private var wrongReady = false

    init {
        val soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(feedbackAttributes())
            .build()
        correctId = soundPool.load(context, R.raw.feedback_correct, 1)
        wrongId = soundPool.load(context, R.raw.feedback_wrong, 1)
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            if (sampleId == correctId) correctReady = true
            if (sampleId == wrongId) wrongReady = true
        }
        pool = soundPool
    }

    override fun correct() = play(correctId, correctReady)

    override fun wrong() = play(wrongId, wrongReady)

    private fun play(soundId: Int, ready: Boolean) {
        if (soundId == 0 || !ready) return
        pool?.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    override fun release() {
        pool?.release()
        pool = null
        correctId = 0
        wrongId = 0
        correctReady = false
        wrongReady = false
    }
}
