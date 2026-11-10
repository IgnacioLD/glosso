package com.glosso.app.platform

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun todayEpochDay(): Long {
    val now = NSDate()
    val localSeconds = now.timeIntervalSince1970.toLong() + NSTimeZone.localTimeZone.secondsFromGMTForDate(now)
    return localSeconds.floorDiv(86_400L)
}
