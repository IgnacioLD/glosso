package com.glosso.app

import android.content.Context
import android.media.AudioAttributes
import com.glosso.engine.model.ExerciseModel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackSoundsTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val played = mutableListOf<FeedbackCue>()

    /** A fresh process with no saved choice, and a player that records instead of sounding. */
    @Before
    fun freshProcess() {
        app.getSharedPreferences("glosso_sounds", Context.MODE_PRIVATE).edit().clear().commit()
        FeedbackSounds.release()
        FeedbackSounds.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(FeedbackSounds, null)
        FeedbackSounds.init(app) {
            object : FeedbackSoundPlayer {
                override fun play(cue: FeedbackCue) { played += cue }
                override fun release() = Unit
            }
        }
    }

    @After
    fun releasePlayer() = FeedbackSounds.release()

    @Test
    fun `sounds are on by default`() {
        assertTrue(FeedbackSounds.enabled.value)
        FeedbackSounds.answer(correct = true, combo = 1)

        assertEquals(listOf(FeedbackCue.CORRECT), played)
    }

    @Test
    fun `the switch off mutes every cue and is remembered`() {
        FeedbackSounds.setEnabled(app, false)
        FeedbackCue.entries.forEach(FeedbackSounds::play)
        FeedbackSounds.answer(correct = false, combo = 0)

        assertTrue(played.isEmpty())
        assertFalse(app.getSharedPreferences("glosso_sounds", Context.MODE_PRIVATE).getBoolean("enabled", true))
    }

    @Test
    fun `feedback cues route to the media stream`() {
        val attributes = feedbackAttributes()

        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
    }

    @Test
    fun `feedback cues stay short sonification cues`() {
        val attributes = feedbackAttributes()

        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
    }

    @Test
    fun `every cue ships a wav and the iOS bundle lists it`() {
        val iosProject = File("../iosApp/project.yml").readText()
        FeedbackCue.entries.forEach { cue ->
            assertTrue("${cue.file}.wav missing", File("../shared/src/androidMain/res/raw/${cue.file}.wav").isFile)
            assertTrue("${cue.file}.wav not in iosApp/project.yml", "res/raw/${cue.file}.wav" in iosProject)
        }
    }

    @Test
    fun `a run of correct answers climbs two steps and a miss is the soft cue`() {
        val cues = (1..6).map { FeedbackSounds.answerCue(correct = true, combo = it) }

        assertEquals(
            listOf(
                FeedbackCue.CORRECT, FeedbackCue.CORRECT,
                FeedbackCue.CORRECT_2, FeedbackCue.CORRECT_2,
                FeedbackCue.CORRECT_3, FeedbackCue.CORRECT_3,
            ),
            cues,
        )
        assertEquals(FeedbackCue.WRONG, FeedbackSounds.answerCue(correct = false, combo = 0))
    }

    @Test
    fun `a session counts the combo and a miss resets it`() {
        val session = ExerciseSession((1..4).map { ExerciseModel(id = "e$it", type = "multiple-choice", prompt = "p") })
        val combos = listOf(true, true, false, true).map { correct ->
            session.submit(correct)
            session.combo.also { session.advance() }
        }

        assertEquals(listOf(1, 2, 0, 1), combos)
    }

    @Test
    fun `a scored take plays at most one cue, biggest moment first`() {
        assertNull(FeedbackSounds.takeCue(celebrated = true, streakGrew = true, score = 100))
        assertEquals(FeedbackCue.STREAK, FeedbackSounds.takeCue(celebrated = false, streakGrew = true, score = 100))
        assertEquals(FeedbackCue.PERFECT, FeedbackSounds.takeCue(celebrated = false, streakGrew = false, score = 100))
        assertNull(FeedbackSounds.takeCue(celebrated = false, streakGrew = false, score = 99))
    }
}
