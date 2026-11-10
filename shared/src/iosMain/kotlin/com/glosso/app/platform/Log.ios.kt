package com.glosso.app.platform

import platform.Foundation.NSLog

actual fun logWarning(tag: String, message: String, error: Throwable?) {
    NSLog("%s", "W/$tag: $message${error?.let { " ($it)" }.orEmpty()}")
}
