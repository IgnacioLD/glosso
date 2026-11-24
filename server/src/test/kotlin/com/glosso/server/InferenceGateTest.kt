package com.glosso.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

class InferenceGateTest {

    @Test
    fun fullQueueIsRefusedAtOnce() = runTest {
        val gate = InferenceGate(maxConcurrent = 1, maxWaiting = 1, waitMs = 10_000)
        assertTrue(gate.admit())
        val queued = async { gate.admit() }
        advanceTimeBy(1)
        assertEquals(1, gate.queued)
        assertFalse(gate.admit()) // the queue is full: no wait
        gate.release()
        assertTrue(queued.await())
        assertEquals(0, gate.queued)
    }

    @Test
    fun waitRunsOut() = runTest {
        val gate = InferenceGate(maxConcurrent = 1, maxWaiting = 4, waitMs = 1_000)
        assertTrue(gate.admit())
        assertFalse(gate.admit())
        assertEquals(0, gate.queued)
    }
}
