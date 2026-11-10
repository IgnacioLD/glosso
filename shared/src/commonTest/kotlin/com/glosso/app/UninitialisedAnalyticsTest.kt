package com.glosso.app

import com.glosso.app.api.ClientEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * iOS wires the foreground and background hooks but never initialises
 * Analytics (docs/IOS-KMP-PLAN.md, phase 6). Those hooks must then record and
 * send nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UninitialisedAnalyticsTest {
    private lateinit var savedScope: CoroutineScope
    private lateinit var savedSender: suspend (List<ClientEvent>) -> Int

    @BeforeTest
    fun setUp() {
        savedScope = Analytics.scope
        savedSender = Analytics.sender
        Analytics.resetForTest()
        // ScreenTracker, which a new session resets, schedules on the main dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Analytics.scope = savedScope
        Analytics.sender = savedSender
        Analytics.resetForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun lifecycleHooksSendNothingWithoutInit() = runTest(UnconfinedTestDispatcher()) {
        var batches = 0
        Analytics.scope = TestScope(testScheduler)
        Analytics.sender = { batches++; 200 }

        Analytics.onForeground()
        Analytics.track("screen_view", mapOf("screen" to "today"))
        Analytics.onBackground()
        Analytics.onForeground()
        Analytics.flush()
        advanceUntilIdle()

        assertEquals(0, batches)
    }
}
