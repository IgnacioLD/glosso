package com.glosso.app.platform

import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId

// Counts deep sleep too, so an anchored time stays right overnight.
actual fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

actual fun epochDayAt(epochMillis: Long): Long =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
