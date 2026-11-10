package com.glosso.server.generation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Periodic aggregation configuration. Default 6 h, always overridable. */
data class SchedulerConfig(val intervalHours: Long = 6) {
    /** Milliseconds between runs; never below one hour. */
    val intervalMillis: Long get() = intervalHours.coerceAtLeast(1) * 3_600_000L

    companion object {
        /** Reads `GLOSSO_SCHEDULE_HOURS` (1..168), defaulting to 6. */
        fun fromEnv(env: (String) -> String? = System::getenv): SchedulerConfig =
            SchedulerConfig((env("GLOSSO_SCHEDULE_HOURS")?.toLongOrNull() ?: 6).coerceIn(1, 168))
    }
}

/**
 * Runs the Demand Aggregator on a fixed period. It works on the same CPU and the
 * same PostgreSQL the API already uses; it adds no broker and no queue.
 *
 * It deliberately waits one interval before the first run: startup stays light,
 * and an operator can trigger a run on demand through the admin route. Runs are
 * best-effort: a failure is logged and retried on the next tick, never propagated.
 */
class DemandScheduler(
    private val service: DemandService,
    private val config: SchedulerConfig,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        log("Demand scheduler started: every ${config.intervalHours} h")
        job = scope.launch {
            while (isActive) {
                delay(config.intervalMillis)
                runOnce()
            }
        }
    }

    /** One aggregation, isolated so a failure never stops the loop. */
    fun runOnce() {
        runCatching { service.aggregate() }
            .onSuccess { log("Demand aggregated: ${it.needClasses} classes, ${it.learners} learners") }
            .onFailure { log("Demand aggregation failed: ${it.message}") }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
