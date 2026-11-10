package com.glosso.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

actual fun platformRecorder(): AudioRecorder = AndroidRecorder()

/**
 * Records mono 16 kHz PCM, the only format the recognizer accepts, and hands back
 * a complete WAV (header included) ready to base64 into a score request.
 */
class AndroidRecorder : AudioRecorder {

    private companion object {
        const val SAMPLE_RATE = Wav.SAMPLE_RATE
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    /** Peak level of the latest buffer, 0..1, for the live waveform. */
    @Volatile
    override var amplitude: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    override fun start() {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Microphone unavailable")
        }
        pcm.reset()
        record = recorder
        running = true
        recorder.startRecording()
        worker = Thread {
            val buffer = ByteArray(bufferSize)
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcm.write(buffer, 0, read)
                    amplitude = Wav.peak(buffer, read)
                }
            }
        }.also { it.start() }
    }

    /** Stops and returns a complete 16 kHz mono WAV file. */
    override fun stop(): ByteArray {
        running = false
        worker?.join(1_000)
        worker = null
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
        amplitude = 0f
        return Wav.pcm16Mono(pcm.toByteArray())
    }
}
