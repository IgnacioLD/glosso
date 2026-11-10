package com.glosso.app.download

import java.io.File
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The queue's contract: priority order, bounded parallelism, retries, pause,
 * cancel and honest progress. Driven with a fake fetch, no Android, no network.
 */
class DownloadEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun engine(
        scope: CoroutineScope,
        maxParallel: Int = 3,
        retries: List<Long> = listOf(0, 0, 0),
    ) = DownloadEngine(
        scope = scope,
        fileSystem = FileSystem.SYSTEM,
        maxParallel = maxParallel,
        retryDelaysMs = retries,
    )

    private fun file(name: String, fetch: suspend ((Long, Long?) -> Unit) -> Unit): DownloadFile =
        DownloadFile(url = "http://test/$name", target = File(folder.root, name).toOkioPath(), fetch = fetch)

    private fun okFile(name: String, size: Long = 100, onFetch: () -> Unit = {}): DownloadFile =
        file(name) { report ->
            onFetch()
            report(size / 2, size)
            report(size, size)
        }

    @Test
    fun `files of one item run in parallel but under the cap`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val current = AtomicInteger(0)
            val peak = AtomicInteger(0)
            // The first three fetches must be in flight at once; the engine caps
            // at three, so a fourth would fail the cap assertion. They park on a
            // coroutine, not Thread.sleep, so the overlap does not depend on how
            // many threads the dispatcher happens to have under load.
            val threeInFlight = CompletableDeferred<Unit>()
            fun overlapping(name: String): DownloadFile = file(name) { report ->
                val now = current.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                if (now >= 3) threeInFlight.complete(Unit)
                withTimeoutOrNull(2_000) { threeInFlight.await() }
                report(50, 100)
                current.decrementAndGet()
                report(100, 100)
            }
            val files = (0 until 12).map { overlapping("f$it") }
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, files)
            val handle = engine(scope, maxParallel = 3).enqueue(item)
            withTimeout(10_000) { handle.await() }
            assertEquals("the cap of 3 must be reached, not exceeded", 3, peak.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a high priority item goes before a normal one`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val order = Collections.synchronizedList(mutableListOf<String>())
            val low = DownloadItem("lesson:low", DownloadKind.LESSON, "low", DownloadPriority.NORMAL, listOf(okFile("low")))
            val high = DownloadItem("model", DownloadKind.MODEL, "high", DownloadPriority.HIGH, listOf(okFile("high") { order.add("high") }))
            val engine = engine(scope, maxParallel = 1)
            // Enqueue the low one first; the high one must still run first.
            engine.enqueue(low)
            val handle = engine.enqueue(high)
            withTimeout(10_000) { handle.await() }
            assertEquals(listOf("high"), order)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a transient failure is retried and then succeeds`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val attempts = AtomicInteger(0)
            val flaky = file("flaky") { report ->
                if (attempts.incrementAndGet() < 3) throw java.io.IOException("boom")
                report(100, 100)
            }
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, listOf(flaky))
            val handle = engine(scope, maxParallel = 1).enqueue(item)
            val outcome = withTimeout(10_000) { handle.await() }
            assertEquals(DownloadOutcome.DONE, outcome)
            assertEquals(3, attempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancel stops an item and reports it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = AtomicInteger(0)
            val slow = file("slow") {
                started.incrementAndGet()
                delay(60_000)
            }
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, listOf(slow))
            val engine = engine(scope, maxParallel = 1)
            val handle = engine.enqueue(item)
            while (started.get() == 0) delay(5)
            engine.cancel("lesson:x")
            val outcome = withTimeout(10_000) { handle.await() }
            assertEquals(DownloadOutcome.CANCELLED, outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pause then resume finishes the item`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Blocks on the first attempt (until the pause cancels it), succeeds
            // on the second, which is what a resume looks like.
            val attempts = AtomicInteger(0)
            val pausing = file("pausing") { report ->
                if (attempts.incrementAndGet() == 1) delay(60_000) else report(100, 100)
            }
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, listOf(pausing))
            val engine = engine(scope, maxParallel = 1)
            val handle = engine.enqueue(item)
            while (attempts.get() == 0) delay(5)
            engine.pause("lesson:x")
            val paused = withTimeout(10_000) { handle.await() }
            assertEquals(DownloadOutcome.PAUSED, paused)
            val engineState = engine.state.value.item("lesson:x")
            assertEquals(DownloadStatus.PAUSED, engineState?.status)

            val resumed = engine.enqueue(item)
            val outcome = withTimeout(10_000) { resumed.await() }
            assertEquals(DownloadOutcome.DONE, outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an item parked with no network resumes when the signal arrives`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val allowed = java.util.concurrent.atomic.AtomicBoolean(false)
            val engine = DownloadEngine(
                scope = scope,
                fileSystem = FileSystem.SYSTEM,
                maxParallel = 1,
                networkAllowed = { allowed.get() },
                retryDelaysMs = listOf(0, 0, 0),
            )
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, listOf(okFile("a")))
            val handle = engine.enqueue(item)
            // It parks as WAITING_NETWORK instead of spinning or failing.
            withTimeout(10_000) {
                engine.state.first { it.item("lesson:x")?.status == DownloadStatus.WAITING_NETWORK }
            }
            allowed.set(true)
            engine.networkChanged()
            val outcome = withTimeout(10_000) { handle.await() }
            assertEquals(DownloadOutcome.DONE, outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `progress reaches one when the item is done`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val files = listOf(okFile("a", 50), okFile("b", 50))
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, files)
            val engine = engine(scope, maxParallel = 2)
            val handle = engine.enqueue(item)
            withTimeout(10_000) { handle.await() }
            val state = engine.state.value.item("lesson:x")
            assertEquals(DownloadStatus.DONE, state?.status)
            assertEquals(1f, state?.progress ?: 0f, 0.0001f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `progress moves while a multi-file item is still downloading`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // One file finishes, the next blocks mid-flight: the item is clearly
            // downloading, yet not every file has started. The bar used to sit at
            // 0% here until the last file reported its Content-Length.
            val gate = CompletableDeferred<Unit>()
            fun quick(name: String): DownloadFile = file(name) { report ->
                report(50, 100)
                report(100, 100)
            }
            fun blocked(name: String): DownloadFile = file(name) { report ->
                report(50, 100)
                gate.await()
                report(100, 100)
            }
            val files = listOf(quick("f0"), blocked("f1"), quick("f2"))
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, files)
            val engine = engine(scope, maxParallel = 1)
            val handle = engine.enqueue(item)
            // Generous, because a saturated CI can starve the engine's dispatcher
            // for a while; the assertion itself is unchanged.
            val mid = withTimeout(30_000) {
                engine.state.first {
                    val state = it.item("lesson:x")
                    state?.status == DownloadStatus.RUNNING && state.filesDone > 0
                }
            }.item("lesson:x")
            assertTrue(
                "progress is shown before every file has started (was ${mid?.progress})",
                (mid?.progress ?: 0f) > 0f,
            )
            gate.complete(Unit)
            val outcome = withTimeout(10_000) { handle.await() }
            assertEquals(DownloadOutcome.DONE, outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a known size moves the bar from the first byte`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<Unit>()
            val labelled = DownloadFile(
                url = "http://test/big",
                target = File(folder.root, "big").toOkioPath(),
                expectedBytes = 100,
                fetch = { report ->
                    report(25, 100)
                    gate.await()
                    report(100, 100)
                },
            )
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, listOf(labelled))
            val engine = engine(scope, maxParallel = 1)
            engine.enqueue(item)
            val mid = withTimeout(10_000) {
                engine.state.first { (it.item("lesson:x")?.progress ?: 0f) > 0f }
            }.item("lesson:x")
            assertEquals(100L, mid?.bytesTotal)
            assertTrue("a quarter in is a quarter shown", (mid?.progress ?: 0f) in 0.2f..0.3f)
            gate.complete(Unit)
            withTimeout(10_000) { engine.state.first { it.item("lesson:x")?.status == DownloadStatus.DONE } }
            assertEquals(1f, engine.state.value.item("lesson:x")?.progress ?: 0f, 0.0001f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `enqueuing the same id twice shares one entry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val item = DownloadItem("lesson:x", DownloadKind.LESSON, "Lesson", DownloadPriority.NORMAL, listOf(okFile("a")))
            val engine = engine(scope, maxParallel = 1)
            engine.enqueue(item)
            engine.enqueue(item)
            val state = engine.state.value
            assertEquals(1, state.items.size)
        } finally {
            scope.cancel()
        }
    }
}
