package com.glosso.app.platform

import java.time.LocalDate

actual fun todayEpochDay(): Long = LocalDate.now().toEpochDay()
