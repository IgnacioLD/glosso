@file:JsModule("./glosso-web.mjs")

package com.glosso.app.audio

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/** Bindings to resources/glosso-web.mjs, the browser side of the web build's audio. */
internal external class Clip(onReady: () -> Unit, onEnd: () -> Unit, onError: () -> Unit) : JsAny {
    fun prepareUrl(url: String)
    fun prepareBytes(bytes: Uint8Array)
    fun play()
    fun pause()
    fun stop()
    fun setRate(rate: Double)
    fun isPlaying(): Boolean
    fun release()
}

internal external fun microphoneAllowed(): Boolean

internal external fun requestMicrophone(): Promise<JsBoolean>

internal external fun startCapture()

internal external fun capturePeak(): Double

internal external fun captureRate(): Int

internal external fun stopCapture(): Float32Array

internal external fun vibrate(millis: Int)
