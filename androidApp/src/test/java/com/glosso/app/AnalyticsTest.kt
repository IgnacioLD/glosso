package com.glosso.app

import android.content.Context
import com.glosso.app.api.ClientEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Usage statistics: queued on disk until the server takes them, dropped for
 * good only when it refuses them, gone when the learner turns them off, and
 * shaped into sessions by the app's foreground and background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalyticsTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val sent = mutableListOf<List<ClientEvent>>()
    private var status: Int? = 202
    private var now = 1_000_000L

    @Before
    fun setUp() {
        app.getSharedPreferences("glosso_analytics", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "analytics_queue.json").delete()
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); status ?: throw java.io.IOException("offline") }
        Analytics.clock = { now }
        Analytics.init(app)
    }

    @After
    fun tearDown() = Analytics.resetForTest()

    private fun names() = sent.flatten().map { it.name }

    @Test
    fun `a build that defaults statistics off sends nothing until the learner turns them on`() {
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.defaultEnabled = false
        Analytics.init(app)
        assertFalse(Analytics.enabled.value)
        Analytics.track("lesson_started")
        Analytics.flush()
        assertTrue(sent.isEmpty())

        Analytics.setEnabled(app, true)
        Analytics.track("lesson_started")
        Analytics.flush()
        assertEquals(listOf("lesson_started"), names())
    }

    @Test
    fun `events wait on disk while offline and are sent once the server takes them`() {
        status = null
        Analytics.track("lesson_started", mapOf("lesson_id" to "ru.lesson.a1.01", "cefr" to "A1"))
        Analytics.flush()
        assertTrue(File(app.filesDir, "analytics_queue.json").isFile)

        // A new process reads the queue back.
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent.add(batch); 202 }
        Analytics.init(app)
        sent.clear()
        Analytics.flush()

        assertEquals(listOf("lesson_started"), names())
        val event = sent.single().single()
        assertEquals(JsonPrimitive("ru.lesson.a1.01"), event.properties["lesson_id"])
        assertEquals(1_000_000L, event.occurredAt)
        assertFalse(File(app.filesDir, "analytics_queue.json").exists())
    }

    @Test
    fun `a server error keeps the queue and a refusal drops it`() {
        status = 503
        Analytics.track("onboarding_started")
        Analytics.flush()
        Analytics.flush()
        assertEquals(2, sent.size)

        status = 400
        Analytics.flush()
        sent.clear()
        Analytics.flush()
        assertTrue("a refused batch is not retried", sent.isEmpty())
    }

    @Test
    fun `turning statistics off drops the queue and records nothing`() {
        status = null
        Analytics.track("onboarding_started")
        Analytics.setEnabled(app, false)
        Analytics.track("onboarding_completed")
        status = 202
        sent.clear()
        Analytics.flush()

        assertTrue(sent.isEmpty())
        assertFalse(Analytics.enabled.value)
        assertFalse(File(app.filesDir, "analytics_queue.json").exists())
        Analytics.resetForTest()
        Analytics.init(app)
        assertFalse("the choice is remembered", Analytics.enabled.value)
    }

    @Test
    fun `a session opens on the first foreground and again only after a long absence`() {
        Analytics.onForeground()
        now += 90_000
        Analytics.track("exercise_completed", mapOf("kind" to "practice", "score_band" to 80, "mastery" to true))
        Analytics.onBackground()
        now += 60_000
        Analytics.onForeground()
        now += 10_000
        Analytics.onBackground()
        now += 31 * 60_000
        Analytics.onForeground()

        assertEquals(
            listOf("app_open", "exercise_completed", "session_end", "session_end", "app_open"),
            names(),
        )
        val events = sent.flatten()
        assertEquals(JsonPrimitive(true), events[0].properties["cold"])
        assertEquals(JsonPrimitive(90), events[2].properties["duration_s"])
        assertEquals(JsonPrimitive(1), events[2].properties["exercises"])
        assertEquals(JsonPrimitive(false), events[4].properties["cold"])
        assertEquals("one session until the long absence", events[0].sessionId, events[3].sessionId)
        assertTrue(events[4].sessionId != events[0].sessionId)
    }

    @Test
    fun `deleting my data clears the queue and asks the server`() = runTest {
        var erased = false
        Analytics.eraser = { erased = true; true }
        status = null
        Analytics.track("onboarding_started")

        assertTrue(Analytics.deleteMyData())
        assertTrue(erased)
        status = 202
        sent.clear()
        Analytics.flush()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `score bands hide the exact score`() {
        assertEquals(70, scoreBand(79))
        assertEquals(100, scoreBand(100))
        assertEquals(0, scoreBand(-3))
    }
}
