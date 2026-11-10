package com.glosso.app.audio

/**
 * The recorder contract the pronunciation loop talks to. Kept as an interface so
 * the loop's state machine (start, stop, cancel on skip) can be exercised with a
 * fake, without a device or microphone.
 */
interface AudioRecorder {
    /** Peak level of the latest buffer, 0..1, for the live waveform. */
    val amplitude: Float

    /** Starts recording; throws when the microphone cannot be opened. */
    fun start()

    /** Stops and returns a complete 16 kHz mono WAV file ([Wav.pcm16Mono]). */
    fun stop(): ByteArray
}

/** This platform's microphone recorder (16 kHz mono PCM, the recognizer's format). */
expect fun platformRecorder(): AudioRecorder
