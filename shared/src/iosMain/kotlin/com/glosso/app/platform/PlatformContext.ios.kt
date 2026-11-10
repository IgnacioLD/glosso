package com.glosso.app.platform

actual abstract class PlatformContext private constructor() {
    /** The one context on iOS: storage and settings are reached through process-wide APIs. */
    companion object INSTANCE : PlatformContext()
}
