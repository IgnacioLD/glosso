package com.glosso.app.platform

actual fun logWarning(tag: String, message: String, error: Throwable?) {
    consoleWarn("W/$tag: $message${error?.let { " ($it)" }.orEmpty()}")
}

private fun consoleWarn(line: String): Unit = js("console.warn(line)")
