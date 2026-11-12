package com.glosso.app

import com.glosso.app.audio.Clip
import com.glosso.app.platform.PlatformContext

actual fun feedbackSoundPlayer(context: PlatformContext): FeedbackSoundPlayer = WebCues()

/**
 * The same two WAV cues, served next to the page (shared/build.gradle.kts copies
 * them from src/androidMain/res/raw) and kept prepared.
 */
private class WebCues : FeedbackSoundPlayer {
    private var correctClip: Clip? = clip("feedback_correct.wav")
    private var wrongClip: Clip? = clip("feedback_wrong.wav")

    private fun clip(url: String) = Clip(onReady = {}, onEnd = {}, onError = {}).also { it.prepareUrl(url) }

    override fun correct() {
        correctClip?.let { it.stop(); it.play() }
    }

    override fun wrong() {
        wrongClip?.let { it.stop(); it.play() }
    }

    override fun release() {
        correctClip?.release()
        wrongClip?.release()
        correctClip = null
        wrongClip = null
    }
}
