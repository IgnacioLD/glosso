package com.glosso.app

import android.content.Context
import com.glosso.app.api.GlossoApi
import com.glosso.app.audio.AudioRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The pronunciation loop must never claim it is recording or scoring once that
 * work is over. Skipping a drill or leaving the screen mid-recording used to keep
 * the recorder running (microphone open, meter stuck on "Recording"), and a score
 * still in flight could land on the next item. These pin both down with a fake
 * recorder and no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PracticeEngineRecordingTest {

    private class FakeRecorder : AudioRecorder {
        var starts = 0
        var stops = 0
        override val amplitude: Float = 0f
        override fun start() {
            starts++
        }

        override fun stop(): ByteArray {
            stops++
            return ByteArray(0)
        }
    }

    private class FakePrefs : ProfilePrefs {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private val app: Context get() = RuntimeEnvironment.getApplication()

    private fun engineWith(recorder: AudioRecorder, scope: CoroutineScope): PracticeEngine {
        val api = GlossoApi(baseUrl = "http://test", engine = MockEngine { respond("{}") }, contentCache = null)
        val profiles = ProfileStore.fromPrefs(FakePrefs(), scope)
        return PracticeEngine(api, scope, app, profiles, OnDeviceScorer, recorder)
    }

    @Test
    fun `clearResult stops a recording in flight and never leaves recording true`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val recorder = FakeRecorder()
        val engine = engineWith(recorder, scope)
        try {
            engine.toggle("The bin is near the gate.", null, emptyList())
            assertTrue("the take is recording", engine.state.value.recording)

            engine.clearResult()

            assertFalse("advancing must not leave the microphone open", engine.state.value.recording)
            assertEquals("the recorder must be stopped once", 1, recorder.stops)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `release resets recording and scoring`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val recorder = FakeRecorder()
        val engine = engineWith(recorder, scope)
        try {
            engine.toggle("The bin is near the gate.", null, emptyList())
            assertTrue(engine.state.value.recording)

            engine.release()

            assertFalse(engine.state.value.recording)
            assertFalse(engine.state.value.scoring)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clearResult cancels a score still in flight so it cannot land later`() {
        // debugInjectTake is inert in release (BuildConfig.DEBUG is false), so the
        // in-flight score it sets up can only be exercised in the debug unit test
        // variant. Skip, do not fail, in the release unit tests.
        assumeTrue("needs the debug-only take injector", BuildConfig.DEBUG)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val recorder = FakeRecorder()
        val api = GlossoApi(
            baseUrl = "http://test",
            engine = MockEngine {
                // Keep the score suspended so it is genuinely "in flight".
                delay(60_000)
                respond("{}")
            },
            contentCache = null,
        )
        val profiles = ProfileStore.fromPrefs(FakePrefs(), scope)
        val engine = PracticeEngine(api, scope, app, profiles, OnDeviceScorer, recorder, debugTools = BuildConfig.DEBUG)
        var scored = false
        engine.onScored = { scored = true }
        try {
            engine.debugInjectTake("The bin is near the gate.", null, "s")
            assertTrue("the score is pending", engine.state.value.scoring)

            engine.clearResult()

            assertFalse("a skipped attempt must not keep the loop busy", engine.state.value.scoring)
            assertFalse("a cancelled attempt must not report a score", scored)
            // A cancelled job must not surface as an error either: the user moved
            // on, and painting "couldn't score" red on the next drill is a bug.
            assertNull("a cancelled attempt must not surface an error", engine.state.value.error)
        } finally {
            scope.cancel()
        }
    }
}
