package com.glosso.app

import com.glosso.app.api.ClientEvent
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.Lock
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.PlatformInfo
import com.glosso.app.platform.appFiles
import com.glosso.app.platform.currentTimeMillis
import com.glosso.app.platform.keyValueStore
import com.glosso.app.platform.platformInfo
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path

/**
 * First-party usage statistics (docs/ANALYTICS-BETA.md): a few product events,
 * queued on disk and sent in batches to our own server. No third-party SDK, no
 * advertising id, no text the learner wrote and no audio; the server keeps only
 * the events and properties of its catalogue.
 *
 * On by default with a notice in onboarding; the learner can turn it off in
 * Settings, which drops the queue and tells the server (`X-Analytics: off`) not
 * to record the score event either. Best-effort: nothing here can block the UI,
 * a take or a launch, and every failure is silent.
 */
object Analytics {

    private const val FILE = "glosso_analytics"
    private const val KEY_ENABLED = "enabled"
    private const val QUEUE_FILE = "analytics_queue.json"
    private const val MAX_QUEUE = 300
    private const val BATCH = 50
    private const val FLUSH_AT = 20

    /** A return after this long in the background is a new session. */
    private const val SESSION_GAP_MS = 30 * 60_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ClientEvent.serializer())

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var prefs: KeyValueStore? = null
    private var fileSystem: FileSystem? = null
    private var queueFile: Path? = null
    private var platform: PlatformInfo? = null
    private val queue = ArrayDeque<ClientEvent>()
    private val queueLock = Lock()
    private val sending = Mutex()
    private val persisting = Lock()

    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sends a batch and returns the HTTP status; the app wires it to its API client. */
    var sender: suspend (List<ClientEvent>) -> Int = { error("Analytics.sender is not wired") }

    /** Sends a crash or error trace to the server; the app wires it to its API client. */
    var traceSender: suspend (CrashTrace) -> Int = { trace ->
        runCatching { appApi.sendCrashTrace(trace) }.getOrElse { error("Analytics.traceSender is not wired") }
    }

    /** Erases this device's events on the server; the app wires it to its API client. */
    var eraser: suspend () -> Boolean = { error("Analytics.eraser is not wired") }
    var clock: () -> Long = ::currentTimeMillis

    /** Whether the on-device scoring model is installed, for `app_open`; null when unknown. */
    var onDeviceModel: () -> Boolean? = { null }

    private var contextRef: PlatformContext? = null
    private var sessionId: String? = null
    private var foregroundSince = 0L
    private var backgroundSince = 0L
    private var coldStart = true
    private var exercises = 0

    fun init(context: PlatformContext) {
        contextRef = context
        if (prefs != null) return
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        platform = platformInfo(context)
        _enabled.value = loaded.getBoolean(KEY_ENABLED, true)
        val files = appFiles(context)
        fileSystem = files.fileSystem
        queueFile = (files.filesDir / QUEUE_FILE).also { file ->
            val saved = runCatching {
                json.decodeFromString(serializer, files.fileSystem.read(file) { readUtf8() })
            }.getOrDefault(emptyList())
            queueLock.withLock { queue.addAll(saved.takeLast(MAX_QUEUE)) }
        }
        // A crash of the previous run, stored by the crash handler as it happened.
        PendingCrash.take(context)?.let { crash ->
            track(
                "app_crash",
                buildMap {
                    put("exception", crash.exception)
                    put("where", crash.where)
                    put("fatal", true)
                    crash.traceId?.let { put("trace_id", it) }
                },
            )
        }
        if (_enabled.value && PendingCrash.pendingTrace(context) != null) {
            scope.launch { sendPendingTrace(context) }
        }
    }

    /**
     * Records one event. [properties] are booleans, numbers or short ids; the
     * server drops anything outside its catalogue.
     */
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        if (!_enabled.value || prefs == null) return
        if (name == "exercise_completed") exercises++
        val event = ClientEvent(
            name = name,
            sessionId = sessionId,
            appVersion = platform?.appVersion.orEmpty(),
            language = LanguagePreferences.current.code,
            properties = JsonObject(
                properties.mapNotNull { (key, value) ->
                    when (value) {
                        is Boolean -> key to JsonPrimitive(value)
                        is Number -> key to JsonPrimitive(value)
                        is String -> key to JsonPrimitive(value)
                        else -> null
                    }
                }.toMap(),
            ),
            occurredAt = clock(),
        )
        val size = queueLock.withLock {
            queue.addLast(event)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            queue.size
        }
        scope.launch {
            persist()
            if (size >= FLUSH_AT) send()
        }
    }

    /**
     * The app came to the foreground: a new session after a long absence.
     * [context] adds the coarse context `app_open` carries (see [openContext]).
     */
    @OptIn(ExperimentalUuidApi::class)
    fun onForeground(context: PlatformContext? = null) {
        val now = clock()
        foregroundSince = now
        if (sessionId == null || now - backgroundSince > SESSION_GAP_MS) {
            sessionId = Uuid.random().toString()
            track("app_open", mapOf("cold" to coldStart) + openContext(context))
            ScreenTracker.onNewSession()
        }
        coldStart = false
        flush()
    }

    /** The app left the foreground: close this stretch of use and send. */
    fun onBackground() {
        val now = clock()
        backgroundSince = now
        if (foregroundSince > 0) {
            track(
                "session_end",
                mapOf("duration_s" to ((now - foregroundSince) / 1000).coerceIn(0, 86_400), "exercises" to exercises),
            )
        }
        exercises = 0
        flush()
    }

    fun flush(): Job = scope.launch { send() }

    /**
     * What `app_open` says about the setting the app runs in, never about the
     * device itself: no model, brand or hardware id. Each value is read on its
     * own and skipped if its source is not ready, so this can never fail a launch.
     */
    private fun openContext(context: PlatformContext?): Map<String, Any?> {
        if (context == null) return emptyMap()
        val info = platformInfo(context)
        return buildMap {
            // iOS adds no OS property yet: a new one needs the privacy policy and
            // the store forms first (docs/IOS-KMP-PLAN.md, phase 6).
            if (info.osName == PlatformInfo.ANDROID) put("android_sdk", info.osMajorVersion)
            runCatching { info.isTablet }.getOrNull()?.let { put("tablet", it) }
            runCatching { info.isDarkMode }.getOrNull()?.let { put("dark_mode", it) }
            runCatching { onDeviceModel() }.getOrNull()?.let { put("ondevice_model", it) }
            runCatching { UiLanguage.current.value }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("ui_language", it) }
            runCatching { VoicePreferences.accent }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("accent", it) }
        }
    }

    internal suspend fun sendPendingTrace(context: PlatformContext): Boolean {
        if (!_enabled.value) return false
        val trace = PendingCrash.pendingTrace(context) ?: return false
        val status = runCatching { traceSender(trace) }.getOrNull()
        return if (status != null && status in 200..299) {
            PendingCrash.clearTrace(context)
            true
        } else {
            false
        }
    }

    /** Sends an error or crash trace asynchronously in [scope]. */
    fun sendTrace(trace: CrashTrace): Job = scope.launch {
        if (!_enabled.value) return@launch
        runCatching { traceSender(trace) }
    }

    fun platformName(): String = platform?.osName ?: PlatformInfo.ANDROID

    fun appVersion(): String? = platform?.appVersion

    fun setEnabled(context: PlatformContext, value: Boolean) {
        init(context)
        if (_enabled.value == value) return
        _enabled.value = value
        prefs?.edit { putBoolean(KEY_ENABLED, value) }
        if (!value) {
            queueLock.withLock { queue.clear() }
            PendingCrash.clear(context)
            scope.launch { persist() }
        }
    }

    /** Erases this device's events on the server and the local queue. */
    suspend fun deleteMyData(): Boolean {
        queueLock.withLock { queue.clear() }
        persist()
        contextRef?.let { PendingCrash.clear(it) }
        return runCatching { eraser() }.getOrDefault(false)
    }

    private suspend fun send() = sending.withLock {
        contextRef?.let { ctx ->
            if (_enabled.value && PendingCrash.pendingTrace(ctx) != null) {
                sendPendingTrace(ctx)
            }
        }
        while (_enabled.value) {
            val batch = queueLock.withLock { queue.take(BATCH) }
            if (batch.isEmpty()) break
            val status = runCatching { sender(batch) }.getOrNull()
            // Offline, a server error or a rate limit: keep the queue for later.
            if (status == null || status >= 500 || status == 429) break
            // Sent, or refused for good (a 4xx would be refused again): drop it.
            queueLock.withLock { queue.removeAll { event -> batch.any { it === event } } }
            persist()
        }
    }

    /** Snapshot and write under one lock, so an older snapshot never lands last. */
    private fun persist() {
        val file = queueFile ?: return
        val fs = fileSystem ?: return
        persisting.withLock {
            val snapshot = queueLock.withLock { queue.toList() }
            runCatching {
                if (snapshot.isEmpty()) {
                    fs.delete(file)
                } else {
                    fs.write(file) { writeUtf8(json.encodeToString(serializer, snapshot)) }
                }
            }
        }
    }

    /** Forgets in-memory state; for tests, which share this singleton. */
    fun resetForTest() {
        prefs = null
        fileSystem = null
        queueFile = null
        platform = null
        contextRef = null
        queueLock.withLock { queue.clear() }
        _enabled.value = true
        sessionId = null
        foregroundSince = 0
        backgroundSince = 0
        coldStart = true
        exercises = 0
        traceSender = { trace ->
            runCatching { appApi.sendCrashTrace(trace) }.getOrElse { error("Analytics.traceSender is not wired") }
        }
    }
}

/** A score as a 10-point band (70 for 70..79), so events never carry the exact value. */
fun scoreBand(score: Int): Int = (score.coerceIn(0, 100) / 10) * 10
