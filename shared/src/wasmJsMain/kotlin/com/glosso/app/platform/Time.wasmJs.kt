package com.glosso.app.platform

actual fun monotonicMillis(): Long = performanceNow().toLong()

actual fun epochDayAt(epochMillis: Long): Long = localEpochDay(epochMillis.toDouble()).toLong()

private fun performanceNow(): Double = js("performance.now()")

private fun localEpochDay(epochMillis: Double): Double =
    js("Math.floor((epochMillis - new Date(epochMillis).getTimezoneOffset() * 60000) / 86400000)")
