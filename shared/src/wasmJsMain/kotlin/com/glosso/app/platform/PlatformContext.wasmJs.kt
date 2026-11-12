package com.glosso.app.platform

actual abstract class PlatformContext private constructor() {
    /** The one context in a browser: storage and settings are reached through `window`. */
    companion object INSTANCE : PlatformContext()
}
