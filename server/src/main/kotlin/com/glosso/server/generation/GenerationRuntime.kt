package com.glosso.server.generation

import com.glosso.server.tts.TtsAssembly
import com.glosso.server.tts.TtsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the full adaptive loop on a fixed period: aggregate demand, then turn the
 * measured gaps into queued generation jobs. It never runs inside a user request.
 *
 * It is best-effort and isolated: a failure is logged and retried on the next
 * tick, never propagated. An operator can force a cycle through the admin route.
 */
class GenerationScheduler(
    private val demand: DemandService,
    private val generation: GenerationService,
    private val config: SchedulerConfig,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        log("Generation scheduler started: every ${config.intervalHours} h")
        job = scope.launch {
            while (isActive) {
                delay(config.intervalMillis)
                runOnce()
            }
        }
    }

    /** One full cycle: demand snapshot, then enqueue gaps. Isolated. */
    fun runOnce(): GenerationRun {
        return runCatching {
            val demandRun = demand.aggregate()
            val generationRun = generation.enqueueGaps()
            log(
                "Generation cycle: classes=${demandRun.needClasses} " +
                    "jobs=${generationRun.jobsCreated} messages=${generationRun.messagesEnqueued}",
            )
            generationRun
        }.onFailure { log("Generation cycle failed: ${it.message}") }
            .getOrElse { GenerationRun(0, 0, 0, 0, 0) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * The assembled phase-2 runtime: the content pool store, the queue, the worker
 * and the scheduler. Owned by the application; close it to stop the loops and
 * release the connections.
 */
class GenerationRuntime(
    val store: GenerationStore,
    val queue: GenerationQueue,
    val service: GenerationService,
    val worker: TtsWorker,
    val scheduler: GenerationScheduler,
    private val scope: CoroutineScope,
    private val workerBlockMs: Long,
    private val log: (String) -> Unit = {},
    private val resources: List<AutoCloseable>,
) : AutoCloseable {

    private var workerJob: Job? = null

    /** Starts the scheduler loop and the single worker loop. */
    fun start() {
        scheduler.start()
        workerJob = scope.launch {
            while (isActive) {
                val handled = runCatching { worker.runOnce() }
                    .onFailure { log("Generation worker loop error: ${it.message}") }
                    .getOrDefault(0)
                if (handled == 0) delay(workerBlockMs.coerceAtLeast(1_000))
            }
        }
    }

    override fun close() {
        workerJob?.cancel()
        workerJob = null
        scheduler.stop()
        resources.forEach { runCatching { it.close() } }
    }
}

/**
 * Composition root of phase 2. Returns null when generation is not configured
 * (no Redis or no TTS provider), so the API keeps serving without it.
 *
 * It shares the control-plane data source: one pool, one migration run. The
 * object store is the env-configured one from the TTS layer (R2/S3, local
 * directory or in-memory).
 */
object GenerationAssembly {

    fun fromEnv(
        database: ControlPlaneDatabase,
        generationStore: GenerationStore,
        demandService: DemandService,
        config: GenerationConfig,
        scope: CoroutineScope,
        schedulerConfig: SchedulerConfig,
        env: Map<String, String> = System.getenv(),
        candidateSource: CandidateSource = TemplateCandidateSource(),
        log: (String) -> Unit = {},
    ): GenerationRuntime? {
        val queue = RedisStreamGenerationQueue.fromEnv(env, log) ?: return null
        val tts = TtsAssembly.fromEnv(env, dataSource = database.dataSource, log = log) ?: run {
            queue.close()
            log("Generation disabled: no TTS provider configured")
            return null
        }

        val service = GenerationService(
            controlStore = database.controlStore,
            generationStore = generationStore,
            queue = queue,
            candidateSource = candidateSource,
            pool = demandService.pool,
            config = config,
            log = log,
        )
        val worker = TtsWorker(
            queue = queue,
            generationStore = generationStore,
            processor = tts.processor,
            config = config,
            log = log,
        )
        val scheduler = GenerationScheduler(
            demand = demandService,
            generation = service,
            config = schedulerConfig,
            scope = scope,
            log = log,
        )
        log("Generation ready: queue=${queue.id}, tts providers=${tts.config.provider}, source=${candidateSource.id}")
        return GenerationRuntime(
            store = generationStore,
            queue = queue,
            service = service,
            worker = worker,
            scheduler = scheduler,
            scope = scope,
            workerBlockMs = config.workerBlockMs,
            log = log,
            resources = listOf(queue, tts),
        )
    }

    /** Provider/model the job hash binds to, read from the TTS environment. */
    fun providerIdentity(env: Map<String, String> = System.getenv()): Pair<String, String> {
        val config = TtsConfig.fromEnv(env)
        val provider = config.provider.lowercase()
        val model = runCatching { config.provider(provider).model }.getOrDefault("")
        return provider to model
    }
}
