package com.glosso.app.platform

import com.glosso.app.CrashFrame
import com.glosso.app.CrashReporter
import kotlin.experimental.ExperimentalNativeApi

/**
 * Kotlin/Native calls the hook for an exception that reached the top of a
 * thread, then terminates. Native signals (a crash inside ONNX Runtime or
 * UIKit) do not pass through here.
 * TODO(ios): not run yet (no Xcode); force a crash in a debug build and check
 * that `app_crash` is reported on the next launch.
 */
@OptIn(ExperimentalNativeApi::class)
actual fun installUncaughtExceptionHandler(onCrash: (Throwable) -> Unit) {
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { throwable ->
        onCrash(throwable)
        previous?.invoke(throwable)
    }
}

actual fun Throwable.typeName(): String = this::class.simpleName.orEmpty()

@OptIn(ExperimentalNativeApi::class)
actual fun Throwable.stackFrames(): List<CrashFrame> = getStackTrace().mapNotNull(CrashReporter::parseNativeFrame)
