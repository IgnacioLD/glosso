package com.glosso.app.platform

import com.glosso.app.TrustedClock
import kotlin.time.Clock

/** Milliseconds since the epoch, as `System.currentTimeMillis()`. */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Milliseconds on a clock that only moves forward and ignores changes to the device clock. */
expect fun monotonicMillis(): Long

/** The day of [epochMillis] in the device's time zone, as days since 1970-01-01. */
expect fun epochDayAt(epochMillis: Long): Long

/** Today in the device's time zone as days since 1970-01-01, by [TrustedClock], not the device clock. */
fun todayEpochDay(): Long = epochDayAt(TrustedClock.nowMillis())
