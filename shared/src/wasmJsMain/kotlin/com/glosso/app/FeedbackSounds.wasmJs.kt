package com.glosso.app

import com.glosso.app.audio.Clip
import com.glosso.app.platform.PlatformContext

actual fun feedbackSoundPlayer(context: PlatformContext): FeedbackSoundPlayer = WebCues()

/**
 * The same WAV cues, served next to the page (shared/build.gradle.kts copies
 * every `feedback_*.wav` from src/androidMain/res/raw) and kept prepared.
 */
private class WebCues : FeedbackSoundPlayer {
    private val clips = FeedbackCue.entries.associateWith { cue ->
        Clip(onReady = {}, onEnd = {}, onError = {}).also { it.prepareUrl("${cue.file}.wav") }
    }

    override fun play(cue: FeedbackCue) {
        clips[cue]?.let { it.stop(); it.play() }
    }

    override fun release() {
        clips.values.forEach { it.release() }
    }
}
