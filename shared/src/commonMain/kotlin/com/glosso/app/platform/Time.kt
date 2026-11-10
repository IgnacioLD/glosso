package com.glosso.app.platform

import kotlin.time.Clock

/** Milliseconds since the epoch, as `System.currentTimeMillis()`. */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Today in the device's time zone as days since 1970-01-01, as `LocalDate.now().toEpochDay()`. */
expect fun todayEpochDay(): Long
