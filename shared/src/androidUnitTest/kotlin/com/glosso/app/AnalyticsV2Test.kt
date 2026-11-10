package com.glosso.app

import android.content.Context
import com.glosso.app.api.ClientEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The v2 usage events (docs/ANALYTICS-BETA.md, section 2b): crash capture keeps
 * only two ids, a screen is reported once per change, and the derived values
 * (read percentage, size, failure reason) stay inside the server's catalogue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalyticsV2Test {

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val sent = mutableListOf<List<ClientEvent>>()
    private val defaultSchedule = ScreenTracker.schedule
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        app.getSharedPreferences("glosso_analytics", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("glosso_crash", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "analytics_queue.json").delete()
        Analytics.resetForTest()
        ScreenTracker.resetForTest()
        ScreenTracker.schedule = { it() }
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.init(app)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        ScreenTracker.schedule = defaultSchedule
        ScreenTracker.resetForTest()
        Analytics.resetForTest()
    }

    private fun events(): List<ClientEvent> {
        Analytics.flush()
        return sent.flatten()
    }

    private fun crash(message: String): Throwable {
        val error = IllegalStateException(message)
        error.stackTrace = arrayOf(
            StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 1),
            StackTraceElement("com.glosso.app.LessonViewModel\$stepTo\$1", "invoke", "LessonViewModel.kt", 2),
        )
        return error
    }

    @Test
    fun `a crash keeps the exception type and the first app frame, never the message`() {
        val secret = "user typed: hola maria"
        CrashReporter.record(app, crash(secret))

        val stored = app.getSharedPreferences("glosso_crash", Context.MODE_PRIVATE).all
        assertTrue(stored.values.none { it.toString().contains("hola") || it.toString().contains("typed") })
        assertEquals(
            StoredCrash("IllegalStateException", "LessonViewModel_stepTo_1.invoke"),
            CrashReporter.describe(crash(secret)),
        )

        val pending = PendingCrash.pendingTrace(app)
        assertNotNull(pending)
        assertEquals("IllegalStateException", pending?.exception)
        assertEquals("LessonViewModel_stepTo_1.invoke", pending?.where)
        assertEquals(true, pending?.fatal)
        assertEquals(1, pending?.causes?.size)
        val cause = pending!!.causes.first()
        assertEquals("IllegalStateException", cause.type)
        assertEquals(2, cause.frames.size)
        assertEquals("java.util.ArrayList", cause.frames[0].className)
        assertEquals("get", cause.frames[0].methodName)
        assertEquals("ArrayList.java", cause.frames[0].fileName)
        assertEquals(1, cause.frames[0].line)
        assertEquals("LessonViewModel.kt", cause.frames[1].fileName)
        assertEquals(2, cause.frames[1].line)
        assertFalse(pending.toString().contains("hola"))
        assertFalse(pending.toString().contains("typed"))
    }

    @Test
    fun `stored values are sanitised to short ids`() {
        assertEquals("a_b_c", PendingCrash.sanitize("a b/c"))
        assertEquals(64, PendingCrash.sanitize("x".repeat(200)).length)
        val anonymous = object : RuntimeException("boom") {}
        anonymous.stackTrace = emptyArray()
        val described = CrashReporter.describe(anonymous)
        assertEquals("Throwable", described.exception)
        assertEquals("unknown", described.where)
    }

    @Test
    fun `the crash handler stores the crash and chains to the previous handler`() {
        var chained: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> chained = e }
        CrashReporter.install(app)
        val error = crash("boom")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

        assertSame(error, chained)
        assertEquals("IllegalStateException", PendingCrash.take(app)?.exception)
    }

    @Test
    fun `the next launch reports the crash once and forgets it`() {
        CrashReporter.record(app, crash("boom"))
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.init(app)

        val crash = events().single { it.name == "app_crash" }
        assertEquals(JsonPrimitive("IllegalStateException"), crash.properties["exception"])
        assertEquals(JsonPrimitive("LessonViewModel_stepTo_1.invoke"), crash.properties["where"])
        assertEquals(JsonPrimitive(true), crash.properties["fatal"])
        assertNotNull(crash.properties["trace_id"])
        assertNull(PendingCrash.take(app))
    }

    @Test
    fun `at next launch the trace endpoint is called and app_crash is emitted with matching trace_id`() {
        var sentTrace: CrashTrace? = null
        CrashReporter.record(app, crash("boom"))
        val storedTrace = PendingCrash.pendingTrace(app)
        assertNotNull(storedTrace)

        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.traceSender = { trace -> sentTrace = trace; 202 }
        Analytics.init(app)

        assertNotNull(sentTrace)
        assertEquals(storedTrace?.traceId, sentTrace?.traceId)
        assertEquals("IllegalStateException", sentTrace?.exception)
        assertEquals("LessonViewModel_stepTo_1.invoke", sentTrace?.where)

        val crashEvent = events().single { it.name == "app_crash" }
        assertEquals(JsonPrimitive("IllegalStateException"), crashEvent.properties["exception"])
        assertEquals(JsonPrimitive("LessonViewModel_stepTo_1.invoke"), crashEvent.properties["where"])
        assertEquals(JsonPrimitive(true), crashEvent.properties["fatal"])
        assertEquals(JsonPrimitive(sentTrace!!.traceId), crashEvent.properties["trace_id"])

        assertNull(PendingCrash.pendingTrace(app))
    }

    @Test
    fun `if trace sending fails it is retried at next launch and app_crash is not duplicated`() {
        CrashReporter.record(app, crash("boom"))
        val originalTraceId = PendingCrash.pendingTrace(app)!!.traceId

        // Launch 1: server returns 500 for trace
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.traceSender = { 500 }
        Analytics.init(app)

        assertEquals(1, events().filter { it.name == "app_crash" }.size)
        // Trace is still pending because server answered 500
        assertNotNull(PendingCrash.pendingTrace(app))

        // Launch 2: server returns 202 for trace
        sent.clear()
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        var retriedTrace: CrashTrace? = null
        Analytics.traceSender = { trace -> retriedTrace = trace; 202 }
        Analytics.init(app)

        // app_crash was NOT duplicated
        assertEquals(0, events().filter { it.name == "app_crash" }.size)
        // Trace was retried and sent
        assertNotNull(retriedTrace)
        assertEquals(originalTraceId, retriedTrace?.traceId)
        // Now pending trace is cleared
        assertNull(PendingCrash.pendingTrace(app))
    }

    @Test
    fun `appError with exception sends trace and adds exception where and trace_id`() {
        var sentTrace: CrashTrace? = null
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.traceSender = { trace -> sentTrace = trace; 202 }
        Analytics.init(app)

        val secret = "sensitive learner info"
        UsageEvents.appError("content_failed", "stories", crash(secret))

        val errorEvent = events().single { it.name == "app_error" }
        assertEquals(JsonPrimitive("content_failed"), errorEvent.properties["kind"])
        assertEquals(JsonPrimitive("stories"), errorEvent.properties["screen"])
        assertEquals(JsonPrimitive("IllegalStateException"), errorEvent.properties["exception"])
        assertEquals(JsonPrimitive("LessonViewModel_stepTo_1.invoke"), errorEvent.properties["where"])

        assertNotNull(sentTrace)
        assertEquals(false, sentTrace?.fatal)
        assertEquals("content_failed", sentTrace?.kind)
        assertEquals("IllegalStateException", sentTrace?.exception)
        assertEquals(JsonPrimitive(sentTrace!!.traceId), errorEvent.properties["trace_id"])

        assertFalse(errorEvent.toString().contains("sensitive"))
        assertFalse(sentTrace.toString().contains("sensitive"))
    }

    @Test
    fun `appError without exception behaves as before without trace`() {
        var sentTrace: CrashTrace? = null
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.traceSender = { trace -> sentTrace = trace; 202 }
        Analytics.init(app)

        UsageEvents.appError("mic_failed", "practice")

        val errorEvent = events().single { it.name == "app_error" }
        assertEquals(JsonPrimitive("mic_failed"), errorEvent.properties["kind"])
        assertEquals(JsonPrimitive("practice"), errorEvent.properties["screen"])
        assertNull(errorEvent.properties["exception"])
        assertNull(errorEvent.properties["where"])
        assertNull(errorEvent.properties["trace_id"])
        assertNull(sentTrace)
    }

    @Test
    fun `with analytics disabled no trace is saved or sent`() {
        var sentTrace: CrashTrace? = null
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.traceSender = { trace -> sentTrace = trace; 202 }
        Analytics.init(app)
        Analytics.setEnabled(app, false)

        CrashReporter.record(app, crash("boom"))
        assertNull(PendingCrash.take(app))
        assertNull(PendingCrash.pendingTrace(app))

        UsageEvents.appError("content_failed", "stories", crash("boom2"))
        assertNull(sentTrace)
        assertEquals(0, sent.size)
    }

    @Test
    fun `no crash is stored while statistics are off`() {
        Analytics.setEnabled(app, false)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashReporter.install(app)
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash("boom"))
        assertNull(PendingCrash.take(app))
    }

    @Test
    fun `app_open carries the context of the run`() {
        Analytics.onForeground(app)
        val open = events().first { it.name == "app_open" }
        assertEquals(JsonPrimitive(34), open.properties["android_sdk"])
        assertTrue(open.properties.containsKey("tablet"))
        assertTrue(open.properties.containsKey("dark_mode"))
        assertTrue(open.properties.containsKey("accent"))
        assertFalse("no device model", open.properties.keys.any { it.contains("model", ignoreCase = true) && it != "ondevice_model" })
    }

    @Test
    fun `a screen is reported once per change and the one beneath again when a sheet closes`() {
        ScreenTracker.setBase("lessons")
        ScreenTracker.setBase("lessons")
        val sheet = Any()
        ScreenTracker.push(sheet, "level_picker")
        ScreenTracker.pop(sheet)
        ScreenTracker.setBase("lessons")

        val screens = events().filter { it.name == "screen_view" }.map { (it.properties["screen"] as JsonPrimitive).content }
        assertEquals(listOf("lessons", "level_picker", "lessons"), screens)
    }

    @Test
    fun `a rotation that removes and re-adds the same screen is not a visit`() {
        var pending: (() -> Unit)? = null
        ScreenTracker.schedule = { pending = it }
        ScreenTracker.setBase("settings")
        pending?.invoke()
        val about = Any()
        ScreenTracker.push(about, "about")
        pending?.invoke()
        // Recreated: the overlay leaves and comes back before the change settles.
        ScreenTracker.pop(about)
        ScreenTracker.push(Any(), "about")
        pending?.invoke()

        val screens = events().filter { it.name == "screen_view" }.map { (it.properties["screen"] as JsonPrimitive).content }
        assertEquals(listOf("settings", "about"), screens)
    }

    @Test
    fun `the shell names its screens as the catalogue does`() {
        assertEquals("today", ShellState().screenName(lessonActive = false, storyReading = false))
        assertEquals("lesson", ShellState(GlossoDestination.LESSONS).screenName(lessonActive = true, storyReading = false))
        assertEquals(
            "story",
            ShellState(GlossoDestination.LIBRARY, LibraryRoute.STORIES).screenName(lessonActive = false, storyReading = true),
        )
        assertEquals("library", ShellState(GlossoDestination.LIBRARY).screenName(lessonActive = false, storyReading = false))
        val routes = LibraryRoute.entries.map {
            ShellState(GlossoDestination.LIBRARY, it).screenName(lessonActive = false, storyReading = false)
        }
        assertEquals(listOf("stories", "exercises", "practice", "offline", "settings"), routes)
    }

    @Test
    fun `read percentage, download size and failure reason`() {
        assertEquals(0, readPercent(0f))
        assertEquals(67, readPercent(2f / 3f))
        assertEquals(100, readPercent(1.4f))
        assertEquals(0, readPercent(Float.NaN))

        assertNull(downloadSizeMb(null))
        assertNull(downloadSizeMb(0))
        assertEquals(1, downloadSizeMb(1))
        assertEquals(1, downloadSizeMb(1024L * 1024L))
        assertEquals(2, downloadSizeMb(1024L * 1024L + 1))
        assertEquals(327, downloadSizeMb(342_000_000L))

        assertEquals("network", downloadFailureReason("java.net.UnknownHostException: api.glossostudio.com"))
        assertEquals("storage", downloadFailureReason("ENOSPC (No space left on device)"))
        assertEquals("server", downloadFailureReason("HTTP 503"))
        assertEquals("damaged", downloadFailureReason("checksum mismatch"))
        assertEquals("error", downloadFailureReason(null))
    }
}
