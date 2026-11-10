package com.glosso.app

import com.glosso.engine.model.ProsodyBonusModel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ProsodyFeedbackTest {

    private fun bonus(
        rhythm: Float = 0.9f,
        tempo: Float = 1f,
        pauses: Float? = 0.95f,
        stress: Float = 0.9f,
        intonation: Float? = 0.85f,
    ) = ProsodyBonusModel(
        points = 3, maxPoints = 5, shape = 0.87f,
        rhythm = rhythm, tempo = tempo, pauses = pauses, stress = stress, intonation = intonation,
        tempoRatio = 1f, extraPauseMs = 0,
    )

    @Test
    fun `a take that follows the audio gets no corrective tip`() {
        assertNull(prosodyFocus(bonus()))
    }

    @Test
    fun `the weakest dimension below the bar drives the tip`() {
        assertEquals(ProsodyDimension.PAUSES, prosodyFocus(bonus(pauses = 0.2f, intonation = 0.6f)))
        assertEquals(ProsodyDimension.INTONATION, prosodyFocus(bonus(intonation = 0.5f)))
        assertEquals(ProsodyDimension.TEMPO, prosodyFocus(bonus(tempo = 0.3f)))
    }

    @Test
    fun `a full bonus is praised even with a weaker dimension`() {
        assertNull(prosodyFocus(bonus(intonation = 0.5f).copy(points = 5)))
    }

    @Test
    fun `unmeasured dimensions are neither shown nor tipped`() {
        val b = bonus(pauses = null, intonation = null)
        assertEquals(listOf(ProsodyDimension.RHYTHM, ProsodyDimension.STRESS), prosodyDimensions(b).map { it.first })
        assertNull(prosodyFocus(b))
    }

    @Test
    fun `the reference clip is the chosen normal-speed voice and never a slow one`() {
        val audio = linkedMapOf("zz-slow:slow" to "a.slow.ogg", "zz-other" to "b.ogg")
        assertEquals("b.ogg", referenceAudioPath(audio))
        assertNull(referenceAudioPath(mapOf("x:slow" to "c.ogg")))
    }
}
