package com.glosso.app.audio

import com.glosso.app.platform.appFiles
import com.glosso.app.platform.PlatformContext
import okio.Path.Companion.toPath
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set

actual fun platformAudioDevice(): AudioDevice = WebAudioDevice()

/**
 * One [Clip] (glosso-web.mjs) per source: a content URL, or a take kept in the
 * in-memory file system. Callbacks arrive on the page's one thread, as
 * `MediaPlayer`'s arrive on the main thread on Android.
 */
private class WebAudioDevice : AudioDevice {
    private var source: AudioSource? = null
    private var prepared: () -> Unit = {}
    private var completed: () -> Unit = {}
    private var failed: () -> Unit = {}
    private var clip: Clip? = null
    private var released = false

    override fun setSource(source: AudioSource): Boolean {
        if (source is AudioSource.File && !files.fileSystem.exists(source.path.toPath())) return false
        this.source = source
        return true
    }

    override fun onPrepared(block: () -> Unit) {
        prepared = block
    }

    override fun onCompletion(block: () -> Unit) {
        completed = block
    }

    override fun onError(block: () -> Unit) {
        failed = block
    }

    override fun prepareAsync(): Boolean {
        val source = source ?: return false
        if (released || clip != null) return false
        val next = Clip(onReady = { prepared() }, onEnd = { completed() }, onError = { failed() })
        clip = next
        when (source) {
            is AudioSource.Url -> next.prepareUrl(source.url)
            is AudioSource.File -> {
                val bytes = runCatching { files.fileSystem.read(source.path.toPath()) { readByteArray() } }
                    .getOrElse { return false }
                next.prepareBytes(Uint8Array(bytes.size).also { array -> bytes.forEachIndexed { i, b -> array[i] = b } })
            }
        }
        return true
    }

    override fun start() {
        clip?.play()
    }

    override fun pause() {
        clip?.pause()
    }

    override fun stop() {
        clip?.stop()
    }

    override fun release() {
        released = true
        clip?.release()
        clip = null
    }

    override fun setSpeed(speed: Float) {
        clip?.setRate(speed.toDouble())
    }

    override val isPlaying: Boolean get() = clip?.isPlaying() == true

    private companion object {
        val files = appFiles(PlatformContext)
    }
}
