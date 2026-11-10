package com.glosso.app.download

import com.glosso.app.platform.Lock
import com.glosso.app.platform.currentTimeMillis
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.concurrent.Volatile
import okio.FileSystem
import okio.Path

/** How a queued item ended. Awaiters and the completion hook both read it. */
enum class DownloadOutcome { DONE, FAILED, CANCELLED, PAUSED }

/** A pause implemented as a cancellation so a running transfer stops at once. */
private class PauseSignal : CancellationException("paused")

/**
 * The single download pipeline: a priority queue of items, each item's files
 * fetched with bounded parallelism, resume-aware transfers, retries with growing
 * waits and a per-item completion hook. It owns no Android type, so the service
 * drives it in production and a fake transport drives it in tests.
 *
 * Files of one item run in parallel up to [maxParallel], so a lesson with a few
 * hundred small assets is no longer a long serial chain; items themselves run
 * one at a time, so the queue stays predictable and the radio bounded. Ordering
 * is priority first, then request order.
 */
class DownloadEngine(
    private val scope: CoroutineScope,
    /** Where the targets live; read for the sizes of files already on disk. */
    private val fileSystem: FileSystem,
    private val maxParallel: Int = DEFAULT_MAX_PARALLEL,
    private val onChanged: (DownloadsState) -> Unit = {},
    private val onCompleted: suspend (DownloadItem) -> Unit = {},
    private val onFailed: suspend (DownloadItem, DownloadFailure) -> Unit = { _, _ -> },
    private val networkAllowed: (DownloadItem) -> Boolean = { true },
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS,
    /** Reads a transport error (an exception message) as the failure the learner is told. */
    private val classifyError: (String) -> DownloadFailure = DownloadFailure::of,
) {

    private class Queued(
        val item: DownloadItem,
        val seq: Long,
        var result: CompletableDeferred<DownloadOutcome>,
        var status: DownloadStatus = DownloadStatus.QUEUED,
        var failure: DownloadFailure? = null,
    ) {
        /** Per-file progress, written by parallel fetches: only touched under [progress]. */
        val progress = Lock()
        val written = mutableMapOf<Int, Long>()
        val totals = mutableMapOf<Int, Long>()
        val completed = mutableSetOf<Int>()

        fun state(): DownloadItemState = progress.withLock {
            val bytesTotal = if (totals.size == item.files.size) totals.values.sum() else null
            val bytesWritten = written.values.sum()
            DownloadItemState(
                id = item.id,
                kind = item.kind,
                title = item.title,
                status = status,
                bytesWritten = bytesWritten,
                bytesTotal = bytesTotal,
                failure = failure,
                filesDone = completed.size,
                filesTotal = item.files.size,
            )
        }
    }

    private val lock = Lock()
    private val items = mutableListOf<Queued>()
    private var seqCounter = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var activeJob: Job? = null
    private var activeId: String? = null

    @Volatile
    private var lastPublishAt = 0L

    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    /**
     * Adds [item] to the queue (or resumes it when its id is already known) and
     * returns a handle that completes with the item's outcome. Copies of the
     * same id share one queue entry, so a double tap never starts two transfers.
     */
    fun enqueue(item: DownloadItem): Deferred<DownloadOutcome> {
        val handle = lock.withLock {
            val existing = items.firstOrNull { it.item.id == item.id }
            if (existing != null) {
                when (existing.status) {
                    DownloadStatus.DONE -> return@withLock CompletableDeferred(DownloadOutcome.DONE)
                    DownloadStatus.RUNNING,
                    DownloadStatus.QUEUED,
                    DownloadStatus.WAITING_NETWORK -> return@withLock existing.result
                    DownloadStatus.PAUSED,
                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELLED -> {
                        existing.result = CompletableDeferred()
                        existing.status = DownloadStatus.QUEUED
                        existing.failure = null
                        existing.result
                    }
                }
            } else {
                val queued = Queued(item, seqCounter++, CompletableDeferred())
                items += queued
                queued.result
            }
        }
        publish()
        startWorker()
        wake.trySend(Unit)
        return handle
    }

    fun pause(id: String) {
        var active = false
        val known = lock.withLock {
            val queued = items.firstOrNull { it.item.id == id } ?: return@withLock false
            if (queued.status.isTerminal()) return@withLock false
            active = activeJob?.isActive == true && activeId == id
            if (!active) queued.status = DownloadStatus.PAUSED
            true
        }
        if (!known) return
        if (active) activeJob?.cancel(PauseSignal()) else publish()
    }

    /** The learner allows this item on a metered connection and it continues. */
    fun allowMetered(id: String) {
        val known = lock.withLock {
            val queued = items.firstOrNull { it.item.id == id } ?: return@withLock false
            queued.item.allowMetered = true
            if (queued.status == DownloadStatus.WAITING_NETWORK) {
                queued.result = CompletableDeferred()
                queued.status = DownloadStatus.QUEUED
            }
            true
        }
        if (!known) return
        publish()
        startWorker()
        wake.trySend(Unit)
    }

    fun resume(id: String) {
        val resumed = lock.withLock {
            val queued = items.firstOrNull { it.item.id == id } ?: return@withLock false
            if (queued.status != DownloadStatus.PAUSED &&
                queued.status != DownloadStatus.WAITING_NETWORK &&
                queued.status != DownloadStatus.FAILED
            ) return@withLock false
            queued.result = CompletableDeferred()
            queued.status = DownloadStatus.QUEUED
            queued.failure = null
            true
        }
        if (!resumed) return
        publish()
        startWorker()
        wake.trySend(Unit)
    }

    fun cancel(id: String) {
        var active = false
        val queued = lock.withLock {
            val queued = items.firstOrNull { it.item.id == id } ?: return@withLock null
            if (queued.status.isTerminal()) return@withLock null
            active = activeJob?.isActive == true && activeId == id
            queued.status = DownloadStatus.CANCELLED
            queued.failure = null
            queued
        } ?: return
        // The running job completes its own handle once the state is published.
        if (active) {
            activeJob?.cancel(CancellationException("cancelled"))
        } else {
            publish()
            queued.result.complete(DownloadOutcome.CANCELLED)
        }
        wake.trySend(Unit)
    }

    fun pauseAll() {
        val active = lock.withLock {
            items.forEach {
                if (!it.status.isTerminal() && it.status != DownloadStatus.RUNNING) {
                    it.status = DownloadStatus.PAUSED
                }
            }
            activeJob?.isActive == true
        }
        if (active) activeJob?.cancel(PauseSignal()) else publish()
    }

    fun resumeAll() {
        lock.withLock {
            items.forEach {
                if (it.status != DownloadStatus.DONE && it.status != DownloadStatus.RUNNING &&
                    it.status != DownloadStatus.QUEUED && it.status != DownloadStatus.CANCELLED
                ) {
                    it.result = CompletableDeferred()
                    it.status = DownloadStatus.QUEUED
                    it.failure = null
                }
            }
        }
        publish()
        startWorker()
        wake.trySend(Unit)
    }

    fun cancelAll() {
        val active = lock.withLock {
            items.forEach {
                if (!it.status.isTerminal()) {
                    it.status = DownloadStatus.CANCELLED
                    it.failure = null
                    it.result.complete(DownloadOutcome.CANCELLED)
                }
            }
            activeJob?.isActive == true
        }
        if (active) activeJob?.cancel(CancellationException("cancelled")) else publish()
        wake.trySend(Unit)
    }

    /** Drops finished items once their outcome has been shown. */
    fun clearFinished() {
        lock.withLock { items.removeAll { it.status.isTerminal() } }
        publish()
    }

    /** Wakes a worker parked waiting for an allowed network. */
    fun networkChanged() {
        wake.trySend(Unit)
    }

    fun close() {
        worker?.cancel()
        activeJob?.cancel()
        wake.close()
    }

    // ----------------------------------------------------------------- worker

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch { loop() }
    }

    private suspend fun loop() {
        while (coroutineContext.isActive) {
            val next = pickNext()
            if (next == null) {
                wake.receive()
                continue
            }
            runItem(next)
        }
    }

    private fun pickNext(): Queued? = lock.withLock {
        items.asSequence()
            .filter { it.status == DownloadStatus.QUEUED }
            .minWithOrNull(compareBy({ it.item.priority.rank }, { it.seq }))
    }

    private suspend fun runItem(queued: Queued) {
        val item = queued.item
        if (!networkAllowed(item)) {
            lock.withLock {
                if (queued.status == DownloadStatus.QUEUED) queued.status = DownloadStatus.WAITING_NETWORK
            }
            publish()
            // Park until the connectivity monitor signals a change. A signal,
            // never a poll: nothing touches the radio while waiting.
            wake.receive()
            lock.withLock {
                if (queued.status == DownloadStatus.WAITING_NETWORK) queued.status = DownloadStatus.QUEUED
            }
            publish()
            return
        }

        // LAZY + explicit start so `activeJob`/`activeId` are set before any byte
        // moves, which is what lets pause/cancel reliably target the live job.
        // Seed the size denominator first: a lesson's files know their size up
        // front (the manifest's per-asset sizes, or the file already on disk),
        // and without this the bar sat at 0% until the last file reported its
        // Content-Length - i.e. for almost the whole transfer.
        item.files.forEachIndexed { index, file ->
            val onDisk = sizeOf(file.target)
            queued.progress.withLock {
                file.expectedBytes?.takeIf { it > 0 }?.let { queued.totals[index] = it }
                onDisk.takeIf { it > 0 }?.let { queued.totals[index] = it }
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            lock.withLock {
                queued.status = DownloadStatus.RUNNING
                queued.failure = null
            }
            publish()
            try {
                coroutineScope {
                    val semaphore = Semaphore(maxParallel)
                    item.files.mapIndexed { index, file ->
                        async {
                            semaphore.withPermit {
                                downloadFile(queued, index, file)
                            }
                        }
                    }.awaitAll()
                }
                val sizes = item.files.map { sizeOf(it.target) }
                queued.progress.withLock {
                    queued.written.keys.toList().forEach { index -> queued.written[index] = sizes[index] }
                }
                lock.withLock { queued.status = DownloadStatus.DONE }
                onCompleted(item)
                publish()
                queued.result.complete(DownloadOutcome.DONE)
            } catch (pause: PauseSignal) {
                // Publish before completing the handle, like DONE and FAILED:
                // whoever awaits it must already see the new status in `state`.
                lock.withLock { queued.status = DownloadStatus.PAUSED }
                publish()
                queued.result.complete(DownloadOutcome.PAUSED)
            } catch (cancel: CancellationException) {
                lock.withLock {
                    if (queued.status != DownloadStatus.CANCELLED) queued.status = DownloadStatus.CANCELLED
                }
                publish()
                queued.result.complete(DownloadOutcome.CANCELLED)
                throw cancel
            } catch (e: Exception) {
                val failure = classifyError(e.message ?: "download failed")
                lock.withLock {
                    queued.status = DownloadStatus.FAILED
                    queued.failure = failure
                }
                onFailed(item, failure)
                publish()
                queued.result.complete(DownloadOutcome.FAILED)
            }
        }
        activeId = item.id
        activeJob = job
        job.start()
        try {
            job.join()
        } finally {
            activeJob = null
            activeId = null
        }
    }

    private suspend fun downloadFile(queued: Queued, index: Int, file: DownloadFile) {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            if (!networkAllowed(queued.item)) {
                lock.withLock {
                    if (queued.status == DownloadStatus.RUNNING) {
                        queued.status = DownloadStatus.WAITING_NETWORK
                    }
                }
                publish()
                wake.receive()
                lock.withLock {
                    if (queued.status == DownloadStatus.WAITING_NETWORK) {
                        queued.status = DownloadStatus.RUNNING
                    }
                }
                publish()
                continue
            }
            try {
                file.fetch { written, total ->
                    queued.progress.withLock {
                        queued.written[index] = written
                        if (total != null && total > 0) queued.totals[index] = total
                    }
                    // The stream calls this per buffer; publishing that often
                    // would rebuild the notification thousands of times. A few
                    // times a second is plenty and keeps the radio quiet.
                    publishThrottled()
                }
                // A skipped (already complete) file reports itself; make sure the
                // recorded total reflects the finished size for an honest bar.
                val size = sizeOf(file.target)
                queued.progress.withLock {
                    queued.totals[index] = size.takeIf { it > 0 } ?: queued.totals[index] ?: -1L
                    if (queued.totals[index] == -1L) queued.totals.remove(index)
                    queued.written[index] = size
                    queued.completed.add(index)
                }
                publish()
                return
            } catch (pause: PauseSignal) {
                throw pause
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (attempt >= retryDelaysMs.size) throw e
                val wait = retryDelaysMs[attempt]
                attempt++
                delay(wait)
            }
        }
    }

    private fun publish() {
        val snapshot = lock.withLock { DownloadsState(items.map { it.state() }) }
        _state.value = snapshot
        onChanged(snapshot)
    }

    private fun publishThrottled() {
        val now = currentTimeMillis()
        if (now - lastPublishAt < PROGRESS_PUBLISH_INTERVAL_MS) return
        lastPublishAt = now
        publish()
    }

    /** The size of [path] on disk, 0 when it is missing (as `File.length()`). */
    private fun sizeOf(path: Path): Long = runCatching { fileSystem.metadataOrNull(path)?.size }.getOrNull() ?: 0L

    private fun DownloadStatus.isTerminal(): Boolean =
        this == DownloadStatus.DONE || this == DownloadStatus.CANCELLED || this == DownloadStatus.FAILED

    companion object {
        /** Enough sockets to hide latency, few enough to stay a good citizen. */
        const val DEFAULT_MAX_PARALLEL = 6
        val DEFAULT_RETRY_DELAYS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)

        /** Progress is republished at most this often while a file streams. */
        const val PROGRESS_PUBLISH_INTERVAL_MS = 200L
    }
}
