package com.glosso.app.audio

/** Where a clip lives: a remote URL or a downloaded file. */
sealed interface AudioSource {
    data class Url(val url: String) : AudioSource
    data class File(val path: String) : AudioSource
}

/**
 * The player contract a piece or a view model talks to. A single implementation
 * owns the sound and hands the turn to one request at a time; whoever loses it
 * hears [onTakenOver] and must reset its own state. That is what keeps the
 * visible state honest when a drill, a word or a take takes the player.
 */
interface ClipPlayer {
    /** Notified true when playback starts, false when it stops, ends or pauses. */
    var onPlayingChanged: ((Boolean) -> Unit)?

    /** The clip passed to [play] is ready and sounding. [owner] is its token. */
    var onPrepared: ((Any?) -> Unit)?

    /** [owner] lost the player to another request and must reset its state. */
    var onTakenOver: ((Any) -> Unit)?

    /** Plays [source] at [speed] (1.0 is normal; a slow listen of a normal-speed clip is lower). */
    fun play(
        source: AudioSource,
        owner: Any? = null,
        onDone: () -> Unit = {},
        onError: (() -> Unit)? = null,
        speed: Float = 1f,
    )

    /** Prepares [source] ahead of time so [play] can start it with no gap. */
    fun preload(source: AudioSource)

    /** Sets the playback rate of the clip now sounding (1.0 is normal). */
    fun setSpeed(speed: Float)

    fun pause()
    fun resume()

    /** Stops the player; [owner] is not told it was superseded by its own stop. */
    fun stop(owner: Any? = null)
}

/**
 * The audio device behind [Player], one per clip: the slice of a platform player
 * (Android's `MediaPlayer`, iOS's `AVPlayer`) the player drives. It is
 * injectable so the takeover arbitration can be exercised from a plain test,
 * without a device or a shadow media player.
 */
interface AudioDevice {
    fun setSource(source: AudioSource): Boolean
    fun onPrepared(block: () -> Unit)
    fun onCompletion(block: () -> Unit)
    fun onError(block: () -> Unit)
    fun prepareAsync(): Boolean
    fun start()
    fun pause()
    fun stop()
    fun release()
    fun setSpeed(speed: Float)
    val isPlaying: Boolean
}

/** A new device for one clip on this platform. */
expect fun platformAudioDevice(): AudioDevice

/**
 * Plays one clip at a time from a URL or a local file. It preloads the next clip
 * so a chained piece (paragraph after paragraph) starts the next segment with no
 * prepare gap, exposes pause/resume, and arbitrates ownership so a second
 * consumer cannot leave the first's state lying.
 */
class Player(
    private val newDevice: () -> AudioDevice = ::platformAudioDevice,
) : ClipPlayer {

    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onPrepared: ((Any?) -> Unit)? = null
    override var onTakenOver: ((Any) -> Unit)? = null

    private val turn = PlaybackTurn()

    private var player: AudioDevice? = null
    private var playing = false

    /** The clip prepared ahead of the current one, to avoid a gap between them. */
    private var next: AudioDevice? = null
    private var nextKey: String? = null
    private var nextReady = false

    fun play(url: String, onDone: () -> Unit = {}) = play(AudioSource.Url(url), onDone = onDone, speed = 1f)

    fun playFile(path: String, onDone: () -> Unit = {}) = play(AudioSource.File(path), onDone = onDone, speed = 1f)

    override fun play(
        source: AudioSource,
        owner: Any?,
        onDone: () -> Unit,
        onError: (() -> Unit)?,
        speed: Float,
    ) {
        turn.take(owner)?.let { onTakenOver?.invoke(it) }

        val key = keyOf(source)
        val reuse = nextReady && nextKey == key
        val ready = if (reuse) next else null
        val stale = if (reuse) null else next
        next = null
        nextKey = null
        nextReady = false

        stopCurrent()
        stale?.let { runCatching { it.release() } }
        if (ready != null) {
            player = ready
            bind(ready, onDone, onError)
            emitPlaying(true)
            onPrepared?.invoke(owner)
            if (speed != 1f) runCatching { ready.setSpeed(speed) }
            runCatching { ready.start() }
            return
        }
        val device = newDevice()
        player = device
        if (!device.setSource(source)) {
            fail(device, onDone, onError)
            return
        }
        device.onPrepared {
            emitPlaying(true)
            onPrepared?.invoke(owner)
            // Before the first syllable, so a slowed listen is slow from the start.
            if (speed != 1f) runCatching { device.setSpeed(speed) }
            device.start()
        }
        device.onCompletion {
            emitPlaying(false)
            turn.finish()
            stopCurrent()
            onDone()
        }
        device.onError {
            emitPlaying(false)
            turn.finish()
            stopCurrent()
            (onError ?: onDone).invoke()
        }
        if (!device.prepareAsync()) fail(device, onDone, onError)
    }

    override fun preload(source: AudioSource) {
        val key = keyOf(source)
        if (nextKey == key && next != null) return
        releaseNext()
        val device = newDevice()
        if (!device.setSource(source)) {
            device.release()
            return
        }
        device.onPrepared { if (next === device) nextReady = true }
        device.onError {}
        if (!device.prepareAsync()) device.release()
        next = device
        nextKey = key
    }

    override fun pause() {
        val device = player ?: return
        runCatching { if (device.isPlaying) device.pause() }
        emitPlaying(false)
    }

    override fun setSpeed(speed: Float) {
        player?.let { runCatching { it.setSpeed(speed) } }
    }

    override fun resume() {
        val device = player ?: return
        runCatching { device.start() }
        emitPlaying(true)
    }

    override fun stop(owner: Any?) {
        turn.release(owner)?.let { onTakenOver?.invoke(it) }
        stopCurrent()
        releaseNext()
    }

    private fun bind(device: AudioDevice, onDone: () -> Unit, onError: (() -> Unit)?) {
        device.onCompletion {
            emitPlaying(false)
            turn.finish()
            stopCurrent()
            onDone()
        }
        device.onError {
            emitPlaying(false)
            turn.finish()
            stopCurrent()
            (onError ?: onDone).invoke()
        }
    }

    private fun stopCurrent() {
        val device = player ?: return
        player = null
        runCatching { if (device.isPlaying) device.stop() }
        runCatching { device.release() }
        if (playing) emitPlaying(false)
    }

    private fun releaseNext() {
        next?.let { runCatching { it.release() } }
        next = null
        nextKey = null
        nextReady = false
    }

    private fun fail(device: AudioDevice, onDone: () -> Unit, onError: (() -> Unit)?) {
        if (player === device) player = null
        runCatching { device.release() }
        emitPlaying(false)
        (onError ?: onDone).invoke()
    }

    private fun keyOf(source: AudioSource): String = when (source) {
        is AudioSource.Url -> source.url
        is AudioSource.File -> source.path
    }

    private fun emitPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        onPlayingChanged?.invoke(value)
    }
}
