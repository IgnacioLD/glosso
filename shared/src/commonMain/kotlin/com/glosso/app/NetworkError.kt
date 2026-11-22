package com.glosso.app

import com.glosso.app.platform.Connectivity
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.getString

/**
 * The honest one-liner for a failed fetch: offline when there is no connection,
 * the device clock when it is wrong (TLS rejects every certificate then), a
 * server problem otherwise. Keeps "no connection" copy from claiming a server
 * error was the learner's network.
 */
suspend fun networkOrServerError(connectivity: Connectivity): String = getString(
    when {
        !connectivity.isOnline() -> Res.string.error_offline
        TrustedClock.deviceClockLooksWrong() -> Res.string.error_clock
        else -> Res.string.error_server
    },
)
