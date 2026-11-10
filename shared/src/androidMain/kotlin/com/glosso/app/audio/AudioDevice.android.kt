package com.glosso.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer

actual fun platformAudioDevice(): AudioDevice = AndroidAudioDevice()

/** The production [AudioDevice]: one Android `MediaPlayer` per clip. */
private class AndroidAudioDevice : AudioDevice {

    private val mp = MediaPlayer()

    override fun setSource(source: AudioSource): Boolean {
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
        )
        return runCatching {
            when (source) {
                is AudioSource.Url -> mp.setDataSource(source.url)
                is AudioSource.File -> mp.setDataSource(source.path)
            }
        }.isSuccess
    }

    override fun onPrepared(block: () -> Unit) {
        mp.setOnPreparedListener { block() }
    }

    override fun onCompletion(block: () -> Unit) {
        mp.setOnCompletionListener { block() }
    }

    override fun onError(block: () -> Unit) {
        mp.setOnErrorListener { _, _, _ -> block(); true }
    }

    override fun prepareAsync(): Boolean = runCatching { mp.prepareAsync() }.isSuccess

    override fun start() = mp.start()
    override fun pause() = mp.pause()
    override fun stop() = mp.stop()
    override fun release() = mp.release()

    override fun setSpeed(speed: Float) {
        // Only valid once prepared; a no-op on a device without a source.
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    override val isPlaying: Boolean get() = mp.isPlaying
}
