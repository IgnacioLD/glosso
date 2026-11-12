package com.glosso.app.audio

import org.khronos.webgl.get

actual fun platformRecorder(): AudioRecorder = WebRecorder()

/**
 * The microphone through `getUserMedia` and an AudioWorklet (glosso-web.mjs),
 * unprocessed (no echo cancellation, noise suppression or gain control, like
 * Android's VOICE_RECOGNITION), downsampled here to the recognizer's 16 kHz.
 * Opening the stream is asynchronous, so the first few milliseconds after
 * [start] can be missing; the learner starts speaking well after that.
 */
private class WebRecorder : AudioRecorder {
    override val amplitude: Float get() = capturePeak().toFloat().coerceIn(0f, 1f)

    override fun start() {
        if (!microphoneAllowed()) error("Microphone unavailable")
        startCapture()
    }

    override fun stop(): ByteArray {
        val rate = captureRate()
        val captured = stopCapture()
        val samples = FloatArray(captured.length) { captured[it] }
        return Wav.pcm16Mono(Wav.downsampleToPcm16(samples, rate))
    }
}
