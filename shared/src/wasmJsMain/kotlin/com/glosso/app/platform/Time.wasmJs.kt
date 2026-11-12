package com.glosso.app.platform

actual fun todayEpochDay(): Long = localEpochDay().toLong()

private fun localEpochDay(): Double =
    js("Math.floor((Date.now() - new Date().getTimezoneOffset() * 60000) / 86400000)")
