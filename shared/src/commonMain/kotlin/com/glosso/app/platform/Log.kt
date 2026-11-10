package com.glosso.app.platform

/** A diagnostic line for developers (logcat on Android); never shown to the learner. */
expect fun logWarning(tag: String, message: String, error: Throwable? = null)
