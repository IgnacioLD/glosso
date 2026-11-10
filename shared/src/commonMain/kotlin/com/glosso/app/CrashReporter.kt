package com.glosso.app

import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.PlatformInfo
import com.glosso.app.platform.installUncaughtExceptionHandler
import com.glosso.app.platform.platformInfo
import com.glosso.app.platform.stackFrames
import com.glosso.app.platform.typeName
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One frame of a crash's stack: the class (fully qualified), method, file, and line. */
@Serializable
data class CrashFrame(
    @SerialName("className") val className: String,
    @SerialName("methodName") val methodName: String,
    @SerialName("fileName") val fileName: String? = null,
    @SerialName("line") val line: Int? = null,
)

/** One exception in a causal chain, with its type and stack frames (never the message). */
@Serializable
data class CrashCause(
    @SerialName("type") val type: String,
    @SerialName("frames") val frames: List<CrashFrame> = emptyList(),
)

/**
 * A full crash or error trace sent to `POST /v1/crash-traces`.
 * Contains up to 4 causes and 64 frames per cause, never exception messages.
 */
@Serializable
data class CrashTrace(
    @SerialName("trace_id") val traceId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("platform") val platform: String,
    @SerialName("fatal") val fatal: Boolean = false,
    @SerialName("kind") val kind: String = "crash",
    @SerialName("exception") val exception: String,
    @SerialName("where") val where: String? = null,
    @SerialName("causes") val causes: List<CrashCause> = emptyList(),
)

/**
 * Remembers that the app crashed, so the next launch can report it as
 * `app_crash` (docs/ANALYTICS-BETA.md) and send the crash trace to the server.
 * Only short ids and sanitized frames are kept; never the exception message.
 *
 * The platform hook runs on a dying process, so the crash is written before it
 * returns and then handed on to whatever handled crashes before (on Android the
 * handler that shows the system dialog and ends the process as usual).
 */
object CrashReporter {

    private const val APP_PACKAGE = "com.glosso"

    fun install(context: PlatformContext) {
        installUncaughtExceptionHandler { throwable ->
            runCatching { if (Analytics.enabled.value) record(context, throwable) }
        }
    }

    /** Stores [throwable]'s summary and trace, committed before the process dies. */
    fun record(context: PlatformContext, throwable: Throwable) {
        if (!Analytics.enabled.value) return
        val traceId = generateTraceId()
        val described = describe(throwable, traceId)
        val info = runCatching { platformInfo(context) }.getOrNull()
        val platform = info?.osName ?: PlatformInfo.ANDROID
        val appVersion = info?.appVersion
        val deviceId = DeviceId.valueOrNull()
        val trace = buildTrace(
            throwable = throwable,
            traceId = traceId,
            fatal = true,
            kind = "crash",
            where = described.where,
            platform = platform,
            appVersion = appVersion,
            deviceId = deviceId,
        )
        PendingCrash.save(context, described, trace)
    }

    fun describe(throwable: Throwable, traceId: String? = null): StoredCrash =
        describe(throwable.typeName(), throwable.stackFrames(), traceId)

    /** The stored form of a crash of type [type] with [frames], top first. */
    fun describe(type: String, frames: List<CrashFrame>, traceId: String? = null): StoredCrash {
        val exception = PendingCrash.sanitize(type).ifEmpty { "Throwable" }
        val frame = frames.firstOrNull { it.className.startsWith(APP_PACKAGE) } ?: frames.firstOrNull()
        val where = frame?.let { PendingCrash.sanitize("${it.className.substringAfterLast('.')}.${it.methodName}") }
            .orEmpty().ifEmpty { "unknown" }
        return StoredCrash(exception, where, traceId)
    }

    @OptIn(ExperimentalUuidApi::class)
    fun generateTraceId(): String = "tr-" + Uuid.random().toString().replace("-", "").take(12)

    /** Builds a sanitized [CrashTrace] without any exception messages or user text. */
    fun buildTrace(
        throwable: Throwable,
        traceId: String,
        fatal: Boolean,
        kind: String = if (fatal) "crash" else "error",
        where: String? = null,
        platform: String = PlatformInfo.ANDROID,
        appVersion: String? = null,
        deviceId: String? = null,
    ): CrashTrace {
        val causes = mutableListOf<CrashCause>()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = throwable
        while (current != null && causes.size < 4 && seen.add(current)) {
            val type = PendingCrash.sanitize(current.typeName()).ifEmpty { "Throwable" }
            val frames = current.stackFrames().take(64).map { frame ->
                CrashFrame(
                    className = PendingCrash.sanitizeFrame(frame.className),
                    methodName = PendingCrash.sanitizeFrame(frame.methodName),
                    fileName = frame.fileName?.let { PendingCrash.sanitizeFrame(it) },
                    line = frame.line,
                )
            }
            causes.add(CrashCause(type = type, frames = frames))
            current = current.cause
        }
        val rootWhere = where ?: run {
            val topFrames = causes.firstOrNull()?.frames.orEmpty()
            val frame = topFrames.firstOrNull { it.className.startsWith(APP_PACKAGE) } ?: topFrames.firstOrNull()
            frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }?.let { PendingCrash.sanitize(it) } ?: "unknown"
        }
        val exception = causes.firstOrNull()?.type ?: "Throwable"
        return CrashTrace(
            traceId = traceId,
            deviceId = deviceId,
            appVersion = appVersion,
            platform = platform,
            fatal = fatal,
            kind = PendingCrash.sanitize(kind).ifEmpty { "crash" },
            exception = exception,
            where = rootWhere,
            causes = causes,
        )
    }

    private val NATIVE_FRAME = Regex("""kfun:([^#\s]+)#([^(\s]+)""")

    /**
     * A Kotlin/Native stack line (`... kfun:com.glosso.app.Foo#bar(){}+12 ...`)
     * as a frame, or null for a line without Kotlin symbols (system frames).
     */
    fun parseNativeFrame(line: String): CrashFrame? =
        NATIVE_FRAME.find(line)?.let {
            CrashFrame(it.groupValues[1], it.groupValues[2], fileName = null, line = -1)
        }
}
