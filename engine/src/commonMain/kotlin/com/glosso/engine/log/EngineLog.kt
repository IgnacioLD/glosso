package com.glosso.engine.log

/**
 * Minimal, platform-agnostic logger for the engine.
 *
 * The engine is pure Kotlin (common) and must never touch `android.util.Log`, so
 * platform logging is funnelled through here. Disabled by default; enable it in
 * debug builds or on the server.
 */
object EngineLog {
    var enabled: Boolean = false

    fun d(tag: String, message: String) {
        if (enabled) println("[$tag] $message")
    }

    /** Builds [message] only when logging is on; for messages on a hot path. */
    inline fun d(tag: String, message: () -> String) {
        if (enabled) println("[$tag] ${message()}")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        printErrorLine("[$tag] $message")
        throwable?.printStackTrace()
    }
}

/** Writes one line to the platform's error stream (stderr). */
internal expect fun printErrorLine(line: String)
