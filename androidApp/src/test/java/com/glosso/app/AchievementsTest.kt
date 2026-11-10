package com.glosso.app

import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    private fun signals(
        sounds: SoundProgress = SoundProgress(),
        phrases: Int = 0,
        today: Long = AchievementSignals.UNSTAMPED,
    ) = AchievementSignals(sounds = sounds, phrasesMastered = phrases, today = today)

    private fun achievement(id: String): Achievement =
        AchievementCatalog.all.first { it.id == id }

    @Test
    fun `mastery needs enough attempts and the threshold`() {
        val profile = PhoneticProfileModel(
            stats = listOf(
                PhonemeStatModel("θ", attempts = 10, correct = 9),
                PhonemeStatModel("r", attempts = 4, correct = 4),
                PhonemeStatModel("l", attempts = 20, correct = 16),
            ),
        )
        // The 90 counts; the perfect but under-sampled sound does not, and neither
        // does the one that is only at 80.
        assertEquals(1, SoundProgress.from(profile, emptyList()).mastered)
    }

    @Test
    fun `a climb needs two days so one noisy take cannot pass`() {
        val oneDay = listOf(PhonemeObservationModel("θ", day = 10, total = 10, correct = 4))
        assertEquals(0, SoundProgress.from(null, oneDay).bestClimb)

        val twoDays = oneDay + PhonemeObservationModel("θ", day = 11, total = 10, correct = 9)
        assertEquals(50, SoundProgress.from(null, twoDays).bestClimb)
    }

    @Test
    fun `a sound seal opens at its target and caps its progress`() {
        val sound3 = achievement("sound3")
        val data = ProgressData()
        val two = signals(sounds = SoundProgress(mastered = 2))
        assertFalse(sound3.earned(data, two))
        assertEquals(2, sound3.progress(data, two))
        assertTrue(sound3.earned(data, signals(sounds = SoundProgress(mastered = 3))))
        assertEquals(3, sound3.progress(data, signals(sounds = SoundProgress(mastered = 7))))
    }

    @Test
    fun `the daily seal follows the honest run, not a stale number`() {
        val daily7 = achievement("daily7")
        val live = ProgressData(dailyStreak = 7, lastTakeDay = 100)
        assertEquals(7, daily7.progress(live, signals(today = 100)))
        // Three days later the run is already broken, so the seal must not claim it.
        assertEquals(0, daily7.progress(live, signals(today = 103)))
    }

    @Test
    fun `a competence seal needs volume and accuracy in all three`() {
        val seal = achievement("competences3")
        val weak = ProgressData(
            skills = mapOf(
                "VOCABULARY" to SkillTally(correct = 18, total = 20),
                "GRAMMAR" to SkillTally(correct = 14, total = 20),
                "COMPREHENSION" to SkillTally(correct = 19, total = 20),
            ),
        )
        assertEquals(2, seal.progress(weak, signals()))

        val strong = ProgressData(
            skills = mapOf(
                "VOCABULARY" to SkillTally(correct = 18, total = 20),
                "GRAMMAR" to SkillTally(correct = 15, total = 20),
                "COMPREHENSION" to SkillTally(correct = 19, total = 20),
            ),
        )
        assertTrue(seal.earned(strong, signals()))
    }

    @Test
    fun `the phrase seal counts takes at mastery`() {
        val phrases15 = achievement("phrases15")
        assertTrue(phrases15.earned(ProgressData(), signals(phrases = 15)))
        assertFalse(phrases15.earned(ProgressData(), signals(phrases = 14)))
    }

    @Test
    fun `an open seal shows its full bar even after the value dips`() {
        val daily7 = achievement("daily7")
        // The seal was earned at seven consecutive days; the run later broke.
        val broken = ProgressData(
            dailyStreak = 7,
            lastTakeDay = 100,
            unlocked = listOf("daily7"),
        )
        val stale = signals(today = 103)
        assertFalse(daily7.earned(broken, stale))
        assertEquals(0, daily7.progress(broken, stale))
        assertEquals(daily7.target, daily7.displayProgress(broken, stale))
    }

    @Test
    fun `an untouched seal still caps its bar at the live value`() {
        val sound3 = achievement("sound3")
        val data = ProgressData()
        val one = signals(sounds = SoundProgress(mastered = 1))
        assertFalse(sound3.isUnlocked(data, one))
        assertEquals(1, sound3.displayProgress(data, one))
    }

    @Test
    fun `every seal declares a unique id and its own copy`() {
        val ids = AchievementCatalog.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(AchievementCatalog.all.all { it.target > 0 })
        // A seal sharing its title or detail with another would read as a duplicate.
        assertEquals(AchievementCatalog.all.size, AchievementCatalog.all.map { it.titleRes }.distinct().size)
    }
}
