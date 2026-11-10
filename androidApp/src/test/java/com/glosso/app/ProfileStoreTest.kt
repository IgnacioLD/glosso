package com.glosso.app

import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreTest {

    private class FakePrefs : ProfilePrefs {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private val sample = PhoneticProfileModel(
        stats = listOf(
            PhonemeStatModel("θ", attempts = 12, correct = 3, errors = mapOf("s" to 7, "t" to 2)),
            PhonemeStatModel("s", attempts = 4, correct = 4),
        ),
    )

    private fun storeWith(prefs: FakePrefs, today: () -> Long = { 100L }): ProfileStore =
        ProfileStore.fromPrefs(prefs, CoroutineScope(Dispatchers.Unconfined), today)

    @Test
    fun `saves and reloads the profile`() {
        val prefs = FakePrefs()
        storeWith(prefs).save(sample)

        assertEquals(sample, storeWith(prefs).current)
    }

    @Test
    fun `reads a legacy bare profile`() {
        val prefs = FakePrefs()
        prefs.values[ProfileStore.KEY] =
            """{"stats":[{"phoneme":"θ","attempts":3,"correct":1,"errors":{"s":2}}]}"""

        assertEquals(1, storeWith(prefs).current?.stats?.size)
    }

    @Test
    fun `corrupt json yields no profile instead of crashing`() {
        val prefs = FakePrefs()
        prefs.values[ProfileStore.KEY] = "{not json"

        assertNull(storeWith(prefs).current)
    }

    @Test
    fun `an unknown version is ignored`() {
        val prefs = FakePrefs()
        prefs.values[ProfileStore.KEY] =
            """{"version":99,"profile":{"stats":[{"phoneme":"θ","attempts":3,"correct":1}]}}"""

        assertNull(storeWith(prefs).current)
    }

    @Test
    fun `codec round trips with a version envelope`() {
        assertEquals(sample, ProfileCodec.decode(ProfileCodec.encode(sample)))
    }

    @Test
    fun `recording an attempt stores the aggregate and a dated observation`() {
        val prefs = FakePrefs()
        val store = storeWith(prefs) { 42L }
        val alignment = listOf(
            PhonemeMatchModel("θ", "s", MatchStatusModel.MISSED),
            PhonemeMatchModel("θ", "s", MatchStatusModel.MISSED),
            PhonemeMatchModel("r", "r", MatchStatusModel.PERFECT),
        )

        store.record(sample, alignment)

        assertEquals(sample, store.current)
        val observed = store.history.value.single { it.phoneme == "θ" }
        assertEquals(42L, observed.day)
        assertEquals(2, observed.total)
        assertEquals(0, observed.correct)
        assertEquals("s", observed.error)
        assertEquals("en.th.stopping", observed.pattern)
        assertEquals(100, store.history.value.single { it.phoneme == "r" }.accuracy)
    }

    @Test
    fun `the dated history survives a reload`() {
        val prefs = FakePrefs()
        storeWith(prefs) { 42L }.record(
            sample,
            listOf(PhonemeMatchModel("θ", "θ", MatchStatusModel.PERFECT)),
        )

        val reloaded = storeWith(prefs).history.value
        assertEquals(1, reloaded.size)
        assertEquals(42L, reloaded.single().day)
    }

    @Test
    fun `a legacy profile loads with an empty history, losing no progress`() {
        val prefs = FakePrefs()
        prefs.values[ProfileStore.KEY] =
            """{"stats":[{"phoneme":"θ","attempts":3,"correct":1,"errors":{"s":2}}]}"""

        val store = storeWith(prefs)
        assertTrue(store.current!!.stats.isNotEmpty())
        assertTrue(store.history.value.isEmpty())
    }

    @Test
    fun `corrupt history data yields an empty log instead of crashing`() {
        val prefs = FakePrefs()
        prefs.values[ProfileStore.HISTORY_KEY] = "{not json"

        assertTrue(storeWith(prefs).history.value.isEmpty())
    }

    @Test
    fun `the demo fixture produces a coherent aggregate and trend`() {
        val prefs = FakePrefs()
        val store = storeWith(prefs) { 1000L }
        store.seedDemo()

        val theta = store.current!!.stats.first { it.phoneme == "θ" }
        assertTrue(theta.attempts > 0)
        assertTrue(store.history.value.any { it.phoneme == "θ" })
    }
}
