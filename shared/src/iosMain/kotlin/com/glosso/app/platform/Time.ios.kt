package com.glosso.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

// Darwin's CLOCK_MONOTONIC keeps counting while the device sleeps.
@OptIn(ExperimentalForeignApi::class)
actual fun monotonicMillis(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()) / 1_000_000uL).toLong()

actual fun epochDayAt(epochMillis: Long): Long {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    val localSeconds = epochMillis / 1000 + NSTimeZone.localTimeZone.secondsFromGMTForDate(date)
    return localSeconds.floorDiv(86_400L)
}
