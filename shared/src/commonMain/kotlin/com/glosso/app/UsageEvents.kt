package com.glosso.app

import kotlin.math.roundToInt

/**
 * Small helpers that shape the v2 usage events (docs/ANALYTICS-BETA.md, section
 * 2b) so every caller sends the same names and values the server's catalogue
 * (`ClientEvents`) accepts.
 */
object UsageEvents {

    /** A reference clip or the learner's own take, played on a tap. */
    fun audioPlayed(source: String, slow: Boolean = false) {
        Analytics.track("audio_played", mapOf("source" to source, "speed" to if (slow) "slow" else "normal"))
    }

    /** A failure the learner saw; [screen] is a `screen_view` name when known. */
    fun appError(kind: String, screen: String? = null, throwable: Throwable? = null) {
        if (throwable == null) {
            Analytics.track("app_error", buildMap { put("kind", kind); screen?.let { put("screen", it) } })
            return
        }
        if (!Analytics.enabled.value) return
        val traceId = CrashReporter.generateTraceId()
        val described = CrashReporter.describe(throwable, traceId)
        val trace = CrashReporter.buildTrace(
            throwable = throwable,
            traceId = traceId,
            fatal = false,
            kind = kind,
            where = described.where,
            platform = Analytics.platformName(),
            appVersion = Analytics.appVersion(),
            deviceId = DeviceId.valueOrNull(),
        )
        Analytics.sendTrace(trace)
        Analytics.track(
            "app_error",
            buildMap {
                put("kind", kind)
                screen?.let { put("screen", it) }
                put("exception", described.exception)
                put("where", described.where)
                put("trace_id", traceId)
            },
        )
    }

    fun settingChanged(setting: String, value: String) {
        Analytics.track("setting_changed", mapOf("setting" to setting, "value" to value))
    }

    fun settingChanged(setting: String, enabled: Boolean) {
        Analytics.track("setting_changed", mapOf("setting" to setting, "enabled" to enabled))
    }
}

/** How much of a story was read, 0..100, from the furthest fraction reached. */
fun readPercent(fraction: Float): Int =
    if (fraction.isNaN()) 0 else (fraction.coerceIn(0f, 1f) * 100).roundToInt()

/** A download size in whole megabytes, rounded up; null when it is not known. */
fun downloadSizeMb(bytes: Long?): Int? {
    if (bytes == null || bytes <= 0) return null
    val mb = (bytes + BYTES_PER_MB - 1) / BYTES_PER_MB
    return mb.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private const val BYTES_PER_MB = 1024L * 1024L

/**
 * A raw transport error reduced to a short reason id. The message itself is
 * never sent: it can carry a URL or a path.
 */
fun downloadFailureReason(raw: String?): String {
    val lower = raw.orEmpty().lowercase()
    return when {
        lower.contains("space") || lower.contains("enospc") -> "storage"
        lower.contains("checksum") || lower.contains("incomplete") || lower.contains("size") -> "damaged"
        lower.contains("http 4") || lower.contains("http 5") -> "server"
        lower.contains("unknownhost") || lower.contains("timeout") || lower.contains("connect") ||
            lower.contains("network") || lower.contains("unreachable") -> "network"
        else -> "error"
    }
}
