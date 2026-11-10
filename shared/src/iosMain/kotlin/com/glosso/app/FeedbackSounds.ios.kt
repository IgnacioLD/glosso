package com.glosso.app

import com.glosso.app.platform.IosDebugEnv
import com.glosso.app.platform.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSBundle

actual fun feedbackSoundPlayer(context: PlatformContext): FeedbackSoundPlayer = BundleCues()

/**
 * The same two WAV cues, read from the app bundle and kept prepared.
 * The Xcode project (iosApp/project.yml) copies `feedback_correct.wav` and
 * `feedback_wrong.wav` from shared/src/androidMain/res/raw into the bundle.
 */
@OptIn(ExperimentalForeignApi::class)
private class BundleCues : FeedbackSoundPlayer {

    private var correctPlayer = player("feedback_correct")
    private var wrongPlayer = player("feedback_wrong")

    private fun player(name: String): AVAudioPlayer? {
        val url = NSBundle.mainBundle.URLForResource(name, withExtension = "wav") ?: return null
        return AVAudioPlayer(contentsOfURL = url, error = null).also {
            if (IosDebugEnv.muted) it.volume = 0f
            it.prepareToPlay()
        }
    }

    override fun correct() {
        correctPlayer?.let { it.currentTime = 0.0; it.play() }
    }

    override fun wrong() {
        wrongPlayer?.let { it.currentTime = 0.0; it.play() }
    }

    override fun release() {
        correctPlayer?.stop()
        wrongPlayer?.stop()
        correctPlayer = null
        wrongPlayer = null
    }
}
