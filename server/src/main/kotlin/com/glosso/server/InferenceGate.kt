package com.glosso.server

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Admission to the CPU-bound recognizer: [maxConcurrent] runs at a time, at most
 * [maxWaiting] requests queued behind them, each for at most [waitMs]. A full
 * queue is refused at once, so a burst gets a fast 503 the app retries instead
 * of everyone waiting out the clock and failing together.
 */
class InferenceGate(maxConcurrent: Int, private val maxWaiting: Int, private val waitMs: Long) {
    private val slots = Semaphore(maxConcurrent)
    private val waiting = AtomicInteger(0)

    val availablePermits: Int get() = slots.availablePermits
    val queued: Int get() = waiting.get()

    /** True with a slot held (pair with [release]); false means answer 503. */
    suspend fun admit(): Boolean {
        if (slots.tryAcquire()) return true
        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet()
            return false
        }
        return try {
            withTimeoutOrNull(waitMs) { slots.acquire(); true } ?: false
        } finally {
            waiting.decrementAndGet()
        }
    }

    fun release() = slots.release()
}
