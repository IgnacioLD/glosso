package com.glosso.app.audio

import com.glosso.app.platform.IosDebugEnv
import com.glosso.app.platform.Lock
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive

actual fun platformRecorder(): AudioRecorder =
    IosDebugEnv.fakeWavPath?.let(::FileRecorder) ?: IosRecorder()

/**
 * Debug only (`GLOSSO_FAKE_WAV`, see [IosDebugEnv]): every take is the given
 * 16 kHz mono WAV, so the simulator reaches cloud scoring with no microphone.
 */
private class FileRecorder(private val path: String) : AudioRecorder {
    override val amplitude: Float get() = 0.5f

    override fun start() = Unit

    override fun stop(): ByteArray =
        FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
}

/**
 * Records the microphone with `AVAudioEngine` and converts it to 16 kHz mono
 * 16-bit PCM, the recognizer's format, as the Android recorder does.
 * TODO(ios): check on a device: the session category and mode (measurement
 * keeps the input unprocessed, like VOICE_RECOGNITION), the conversion from the
 * hardware rate, and what happens on an interruption. On the simulator (Mac
 * microphone) activating the session once blocked the main thread inside
 * CoreAudio; if a device shows any delay, activate it off the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosRecorder : AudioRecorder {

    private val lock = Lock()
    private var engine: AVAudioEngine? = null
    private var pcm = ByteArray(0)
    private var length = 0

    @Volatile
    override var amplitude: Float = 0f
        private set

    override fun start() {
        if (engine != null) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            mode = AVAudioSessionModeMeasurement,
            options = AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)

        val fresh = AVAudioEngine()
        val input = fresh.inputNode
        val inputFormat = input.outputFormatForBus(0u)
        val target = AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = Wav.SAMPLE_RATE.toDouble(),
            channels = 1u,
            interleaved = true,
        )
        val converter = AVAudioConverter(fromFormat = inputFormat, toFormat = target)
        lock.withLock { length = 0 }
        input.installTapOnBus(0u, bufferSize = 4096u, format = inputFormat) { buffer, _ ->
            if (buffer != null) append(convert(converter, buffer, target))
        }
        fresh.prepare()
        if (!fresh.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            error("Microphone unavailable")
        }
        engine = fresh
    }

    private fun convert(converter: AVAudioConverter, buffer: AVAudioPCMBuffer, target: AVAudioFormat): ByteArray {
        val ratio = Wav.SAMPLE_RATE / buffer.format.sampleRate
        val capacity = (buffer.frameLength.toDouble() * ratio).toUInt() + 1u
        val out = AVAudioPCMBuffer(pCMFormat = target, frameCapacity = capacity)
        var given = false
        converter.convertToBuffer(out, error = null) { _, status ->
            if (given) {
                status?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                null
            } else {
                given = true
                status?.pointed?.value = AVAudioConverterInputStatus_HaveData
                buffer
            }
        }
        val frames = out.frameLength.toInt()
        val channel = out.int16ChannelData?.get(0) ?: return ByteArray(0)
        return channel.readBytes(frames * 2)
    }

    private fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        amplitude = Wav.peak(bytes)
        lock.withLock {
            if (length + bytes.size > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, length + bytes.size))
            bytes.copyInto(pcm, length)
            length += bytes.size
        }
    }

    override fun stop(): ByteArray {
        engine?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
        engine = null
        amplitude = 0f
        AVAudioSession.sharedInstance().setActive(false, error = null)
        return Wav.pcm16Mono(lock.withLock { pcm.copyOf(length) })
    }
}
