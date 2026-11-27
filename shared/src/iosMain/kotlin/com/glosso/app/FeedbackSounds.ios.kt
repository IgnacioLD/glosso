package com.glosso.app

import com.glosso.app.platform.IosDebugEnv
import com.glosso.app.platform.PlatformContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSBundle

actual fun feedbackSoundPlayer(context: PlatformContext): FeedbackSoundPlayer = BundleCues()

/**
 * The same WAV cues, read from the app bundle and kept prepared. The Xcode
 * project (iosApp/project.yml) copies every `feedback_*.wav` from
 * shared/src/androidMain/res/raw into the bundle.
 */
@OptIn(ExperimentalForeignApi::class)
private class BundleCues : FeedbackSoundPlayer {

    private val players = FeedbackCue.entries.mapNotNull { cue -> player(cue.file)?.let { cue to it } }.toMap()

    private fun player(name: String): AVAudioPlayer? {
        val url = NSBundle.mainBundle.URLForResource(name, withExtension = "wav") ?: return null
        return AVAudioPlayer(contentsOfURL = url, error = null).also {
            if (IosDebugEnv.muted) it.volume = 0f
            it.prepareToPlay()
        }
    }

    override fun play(cue: FeedbackCue) {
        players[cue]?.let { it.currentTime = 0.0; it.play() }
    }

    override fun release() {
        players.values.forEach { it.stop() }
    }
}
