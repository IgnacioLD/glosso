package com.glosso.app.platform

import com.glosso.app.CrashFrame

actual fun installUncaughtExceptionHandler(onCrash: (Throwable) -> Unit) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        onCrash(throwable)
        previous?.uncaughtException(thread, throwable)
    }
}

actual fun Throwable.typeName(): String = javaClass.simpleName

actual fun Throwable.stackFrames(): List<CrashFrame> = stackTrace.map {
    CrashFrame(
        className = it.className,
        methodName = it.methodName,
        fileName = it.fileName,
        line = it.lineNumber.takeIf { l -> l >= 0 } ?: -1,
    )
}
