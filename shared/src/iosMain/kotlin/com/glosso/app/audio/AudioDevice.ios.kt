package com.glosso.app.audio

import com.glosso.app.platform.IosDebugEnv
import com.glosso.app.platform.logWarning
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLog
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.darwin.DISPATCH_QUEUE_PRIORITY_HIGH
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.math.roundToInt

actual fun platformAudioDevice(): AudioDevice = IosAudioDevice()

/**
 * One `AVAudioPlayer` per clip, for remote URLs and downloaded files alike.
 *
 * AVFoundation cannot decode Ogg Vorbis, the format of every content clip, so
 * a source whose bytes start with an Ogg page is decoded with stb_vorbis
 * ([VorbisDecoder]) off the main thread into a 16-bit WAV at its own sample
 * rate and channel count, and the player plays that. Other sources (the
 * learner's WAV takes) go to the player as they are. Recently decoded clips are
 * kept in [PcmCache], so replays do not decode again.
 *
 * Slow listening matches Android's `PlaybackParams.setSpeed`: the tempo
 * changes and the pitch does not (`enableRate`, AVAudioPlayer's time-pitch).
 * Callbacks run on the main thread, as `MediaPlayer`'s do on Android.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosAudioDevice : AudioDevice {

    private var source: AudioSource? = null
    private var name: String = ""
    private var player: AVAudioPlayer? = null
    private var delegate: PlayerDelegate? = null
    private var spillPath: String? = null
    private var download: NSURLSessionDataTask? = null
    private var preparing = false
    private var released = false
    private var speed = 1f

    private var prepared: (() -> Unit)? = null
    private var completed: (() -> Unit)? = null
    private var failed: (() -> Unit)? = null

    /** For the debug trace: the clip's length and how long it has sounded. */
    private var clipSeconds = 0.0
    private var playedSeconds = 0.0
    private var startedAt: Double? = null

    override fun setSource(source: AudioSource): Boolean {
        when (source) {
            is AudioSource.Url -> {
                val url = NSURL.URLWithString(source.url) ?: return false
                name = url.lastPathComponent ?: source.url
            }
            is AudioSource.File -> {
                if (!NSFileManager.defaultManager.fileExistsAtPath(source.path)) return false
                name = source.path.substringAfterLast('/')
            }
        }
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
        if (released || preparing || player != null) return false
        preparing = true
        when (source) {
            is AudioSource.File -> background {
                load(source, NSData.dataWithContentsOfFile(source.path, NSDataReadingMappedIfSafe, null))
            }
            is AudioSource.Url -> fetch(source)
        }
        return true
    }

    private fun fetch(source: AudioSource.Url) {
        val url = NSURL.URLWithString(source.url) ?: return main { fail("bad URL") }
        val request = NSMutableURLRequest.requestWithURL(url).apply { setTimeoutInterval(FETCH_TIMEOUT_SECONDS) }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
            when {
                error != null -> main { fail("download failed: ${error.localizedDescription}") }
                status != null && status !in 200..299 -> main { fail("download failed: HTTP $status") }
                // Already on a URLSession queue, not the main thread.
                else -> load(source, data)
            }
        }
        download = task
        task.resume()
    }

    /** Off the main thread: decodes an Ogg stream (or keeps the bytes as they are), then opens the player on main. */
    private fun load(source: AudioSource, data: NSData?) {
        if (data == null || data.length == 0uL) return main { fail("no data") }
        if (!VorbisDecoder.isOgg(data)) {
            return main { open(Playable.Encoded(source, data), trace = "not Ogg, played as it is") }
        }
        val key = cacheKey(source)
        PcmCache.shared.get(key)?.let { cached ->
            return main { open(Playable.Pcm(cached), trace = "ogg ${describe(cached)}, from cache") }
        }
        val began = now()
        val decoded = try {
            VorbisDecoder.decode(data)
        } catch (e: VorbisException) {
            return main { fail("cannot decode Ogg Vorbis: ${e.message}") }
        }
        val millis = ((now() - began) * 1000).roundToInt()
        if (decoded is DecodedAudio.InMemory) PcmCache.shared.put(key, decoded)
        main { open(Playable.Pcm(decoded), trace = "ogg ${describe(decoded)}, decoded in $millis ms") }
    }

    /** On main: the player for what [load] produced, unless the device was released meanwhile. */
    private fun open(playable: Playable, trace: String) {
        download = null
        val spill = ((playable as? Playable.Pcm)?.audio as? DecodedAudio.OnDisk)?.path
        if (released) {
            spill?.let(SpillFiles::delete)
            return
        }
        spillPath = spill
        val next = when (playable) {
            is Playable.Pcm -> when (val audio = playable.audio) {
                is DecodedAudio.InMemory -> AVAudioPlayer(data = audio.wav, error = null)
                is DecodedAudio.OnDisk -> AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(audio.path), error = null)
            }
            is Playable.Encoded -> when (val source = playable.source) {
                is AudioSource.File -> AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(source.path), error = null)
                is AudioSource.Url -> AVAudioPlayer(data = playable.data, error = null)
            }
        } ?: return fail("AVAudioPlayer cannot open it ($trace)")
        val listener = PlayerDelegate(
            onFinish = { ok -> if (ok) finish() else fail("playback did not finish") },
            onDecodeError = { error -> fail("playback error: ${error?.localizedDescription}") },
        )
        next.delegate = listener
        // Before prepareToPlay, or the rate cannot change later.
        next.enableRate = true
        if (IosDebugEnv.muted) next.volume = 0f
        if (!next.prepareToPlay()) return fail("AVAudioPlayer cannot prepare it ($trace)")
        player = next
        delegate = listener
        preparing = false
        clipSeconds = next.duration
        val from = if (source is AudioSource.File) "file" else "url"
        trace("ready $name ($from): $trace, ${seconds(next.duration)}")
        prepared?.invoke()
    }

    private fun finish() {
        if (released) return
        stopClock()
        trace(
            "done $name after ${seconds(playedSeconds)} at ${speed}x " +
                "(clip ${seconds(clipSeconds)}, expected ${seconds(clipSeconds / speed)})",
        )
        completed?.invoke()
    }

    private fun fail(reason: String) {
        download = null
        preparing = false
        if (released) return
        logWarning("Audio", "Cannot play $name: $reason")
        failed?.invoke()
    }

    override fun start() {
        val p = player ?: return
        p.rate = speed
        if (p.play() && startedAt == null) startedAt = now()
    }

    override fun pause() {
        player?.pause()
        stopClock()
    }

    override fun stop() {
        player?.let {
            it.stop()
            it.currentTime = 0.0
        }
        stopClock()
    }

    override fun release() {
        released = true
        download?.cancel()
        download = null
        player?.let {
            it.delegate = null
            it.stop()
        }
        player = null
        delegate = null
        spillPath?.let(SpillFiles::delete)
        spillPath = null
    }

    override fun setSpeed(speed: Float) {
        // AVAudioPlayer's range; the app uses 1.0 and SLOW_RATE.
        this.speed = speed.coerceIn(0.5f, 2f)
        player?.rate = this.speed
    }

    override val isPlaying: Boolean get() = player?.playing == true

    private fun stopClock() {
        startedAt?.let { playedSeconds += now() - it }
        startedAt = null
    }

    /** A clip to hand to AVAudioPlayer: decoded Ogg, or bytes it reads itself (WAV). */
    private sealed interface Playable {
        class Pcm(val audio: DecodedAudio) : Playable
        class Encoded(val source: AudioSource, val data: NSData) : Playable
    }

    private companion object {
        const val FETCH_TIMEOUT_SECONDS = 30.0

        /** A decoded clip is reused for the same URL, or the same file with the same size and date. */
        fun cacheKey(source: AudioSource): String = when (source) {
            is AudioSource.Url -> source.url
            is AudioSource.File -> {
                val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(source.path, null)
                val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue
                val modified = (attributes?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970
                "${source.path}|$size|$modified"
            }
        }

        fun describe(audio: DecodedAudio): String =
            "${audio.sampleRate} Hz x${audio.channels}, ${audio.frames} frames" +
                if (audio is DecodedAudio.OnDisk) ", spilled to disk" else ""

        fun seconds(value: Double): String = "${(value * 1000).roundToInt() / 1000.0} s"

        fun now(): Double = NSDate().timeIntervalSince1970

        fun trace(message: String) {
            if (IosDebugEnv.debug) NSLog("%s", "I/Audio: $message")
        }

        fun background(block: () -> Unit) {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_HIGH.toLong(), 0u)) { block() }
        }

        fun main(block: () -> Unit) {
            if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }
}

/** AVAudioPlayer keeps its delegate weakly; the device holds this one. */
private class PlayerDelegate(
    private val onFinish: (Boolean) -> Unit,
    private val onDecodeError: (NSError?) -> Unit,
) : NSObject(), AVAudioPlayerDelegateProtocol {

    override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
        onMain { onFinish(successfully) }
    }

    override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
        onMain { onDecodeError(error) }
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}
