package com.glosso.app

import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.serialization.json.Json

/** A crash as `app_crash` reports it: the exception's type, the first app frame, and its trace id. */
data class StoredCrash(val exception: String, val where: String, val traceId: String? = null)

/**
 * The crash of the previous run, kept so the next launch can report it as
 * `app_crash` (docs/ANALYTICS-BETA.md) and send the crash trace to the server.
 * The summary is cleared once read so `app_crash` is emitted once; the serialized
 * trace is kept until the server confirms receipt (2xx).
 */
object PendingCrash {

    private const val FILE = "glosso_crash"
    private const val KEY_EXCEPTION = "exception"
    private const val KEY_WHERE = "where"
    private const val KEY_TRACE_ID = "trace_id"
    private const val KEY_TRACE = "trace"
    private const val MAX = 64
    private val UNSAFE = Regex("[^A-Za-z0-9._:-]")
    private val FRAME_UNSAFE = Regex("[^A-Za-z0-9._$<>-]")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Stores [crash] and optional [trace], committed before the process dies. */
    fun save(context: PlatformContext, crash: StoredCrash, trace: CrashTrace? = null) {
        keyValueStore(context, FILE).edit(commit = true) {
            putString(KEY_EXCEPTION, crash.exception)
            putString(KEY_WHERE, crash.where)
            crash.traceId?.let { putString(KEY_TRACE_ID, it) }
            trace?.let { putString(KEY_TRACE, json.encodeToString(CrashTrace.serializer(), it)) }
        }
    }

    /** The stored crash summary, if any, cleared as it is read so it is reported once. */
    fun take(context: PlatformContext): StoredCrash? {
        val prefs = keyValueStore(context, FILE)
        val exception = prefs.getString(KEY_EXCEPTION) ?: return null
        val where = prefs.getString(KEY_WHERE).orEmpty()
        val traceId = prefs.getString(KEY_TRACE_ID)
        prefs.edit(commit = true) {
            remove(KEY_EXCEPTION)
            remove(KEY_WHERE)
            remove(KEY_TRACE_ID)
        }
        return StoredCrash(
            sanitize(exception).ifEmpty { "Throwable" },
            sanitize(where).ifEmpty { "unknown" },
            traceId?.let { sanitize(it) },
        )
    }

    /** The pending serialized trace, if any, kept until the server answers 2xx. */
    fun pendingTrace(context: PlatformContext): CrashTrace? {
        val raw = keyValueStore(context, FILE).getString(KEY_TRACE) ?: return null
        return runCatching { json.decodeFromString(CrashTrace.serializer(), raw) }.getOrNull()
    }

    fun trace(context: PlatformContext): CrashTrace? = pendingTrace(context)

    /** Clears the pending trace after the server confirms receipt. */
    fun clearTrace(context: PlatformContext) {
        keyValueStore(context, FILE).edit(commit = true) {
            remove(KEY_TRACE)
        }
    }

    /** Clears the entire crash store (summary and trace). */
    fun clear(context: PlatformContext) {
        keyValueStore(context, FILE).edit(commit = true) {
            clear()
        }
    }

    /** Only id characters, at most 64 of them: what the server's catalogue accepts. */
    fun sanitize(raw: String): String = raw.replace(UNSAFE, "_").take(MAX)

    /** Code identifier characters bounded to 256 characters for stack frames. */
    fun sanitizeFrame(raw: String): String = raw.replace(FRAME_UNSAFE, "_").take(256)
}
