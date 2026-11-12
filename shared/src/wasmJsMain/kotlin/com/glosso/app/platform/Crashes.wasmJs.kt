package com.glosso.app.platform

import com.glosso.app.CrashFrame

// shortcut: no crash reports from the web, as on iOS (CrashReporter not installed);
// hook window.onerror when web analytics are covered by the privacy policy.
actual fun installUncaughtExceptionHandler(onCrash: (Throwable) -> Unit) = Unit

actual fun Throwable.typeName(): String = this::class.simpleName.orEmpty()

actual fun Throwable.stackFrames(): List<CrashFrame> = emptyList()
