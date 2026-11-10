package com.glosso.app

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * `reconcile` runs when the profile opens: it persists the seals the live signals
 * already earn, with no celebration. It is the path that recovers a seal reached
 * before this build, so it must persist exactly what is earned and nothing else.
 *
 * One test method on purpose: [ProgressStore] is a singleton whose `init` is a
 * no-op after the first call, so keeping all its state transitions in one case
 * avoids leaning on a fresh application between methods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressStoreReconcileTest {

    @Test
    fun `reconcile persists earned seals, silently and once`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val today = LocalDate.now().toEpochDay()

        ProgressStore.init(context)
        ProgressStore.recordAnswer(correct = true)
        ProgressStore.consumeCelebration()
        assertFalse("sound3" in ProgressStore.state.value.unlocked)

        val signals = AchievementSignals(sounds = SoundProgress(mastered = 3), today = today)
        ProgressStore.reconcile(signals)
        // Idempotent: the already-recorded seal is not added again.
        ProgressStore.reconcile(signals)

        val after = ProgressStore.state.value
        assertTrue("sound3" in after.unlocked)
        // A seal this far short of its target must not be carried along.
        assertFalse("sound10" in after.unlocked)
        assertTrue(after.unlocked.size == after.unlocked.distinct().size)
        // Reconciliation is silent: it never steals the celebration overlay.
        assertNull(ProgressStore.celebration.value)

        // Persisted, so a cold read of the store sees it too.
        val saved = context.getSharedPreferences("glosso_stats", Context.MODE_PRIVATE)
            .getString("progress", null)
        assertNotNull(saved)
        assertTrue(saved!!.contains("sound3"))
    }
}
