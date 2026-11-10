package com.glosso.engine.profile

import com.glosso.engine.model.PhonemeObservationModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorPatternDetectorTest {

    private fun miss(day: Long, error: String = "s", phoneme: String = "θ") =
        PhonemeObservationModel(phoneme = phoneme, day = day, total = 10, correct = 0, error = error)

    private fun landed(day: Long, phoneme: String = "θ") =
        PhonemeObservationModel(phoneme = phoneme, day = day, total = 10, correct = 10, error = null)

    @Test
    fun `one isolated failure is not a pattern`() {
        val history = listOf(miss(1))
        assertNull(ErrorPatternDetector.detectOne(history, "θ"))
        assertTrue(ErrorPatternDetector.detect(history).isEmpty())
    }

    @Test
    fun `two failures are still not a pattern`() {
        val history = listOf(miss(1), miss(2))
        assertNull(ErrorPatternDetector.detectOne(history, "θ"))
    }

    @Test
    fun `the same error three times in a row is a pattern`() {
        val history = listOf(miss(1), miss(2), miss(3))
        val pattern = ErrorPatternDetector.detectOne(history, "θ")
        assertNotNull(pattern)
        assertEquals("s", pattern.error)
        assertEquals(3, pattern.evidence)
    }

    @Test
    fun `confidence grows with more of the same evidence`() {
        val three = ErrorPatternDetector.detectOne(listOf(miss(1), miss(2), miss(3)), "θ")!!
        val five = ErrorPatternDetector.detectOne((1..5).map { miss(it.toLong()) }, "θ")!!
        assertTrue(five.confidence > three.confidence, "more evidence must raise confidence")
    }

    @Test
    fun `a landing breaks the streak and drops the pattern`() {
        val history = listOf(miss(1), miss(2), miss(3), landed(4))
        assertNull(ErrorPatternDetector.detectOne(history, "θ"), "a new success must clear the pattern")
    }

    @Test
    fun `different errors in a row do not name one pattern`() {
        val history = listOf(miss(1, "s"), miss(2, "t"), miss(3, "f"))
        assertNull(ErrorPatternDetector.detectOne(history, "θ"))
    }

    @Test
    fun `an improving sound keeps the evidence but loses confidence`() {
        val steady = (1..5).map { miss(it.toLong()) }
        val history = listOf(
            PhonemeObservationModel("θ", 1, 10, 0, "s"),
            PhonemeObservationModel("θ", 2, 10, 2, "s"),
            PhonemeObservationModel("θ", 3, 10, 4, "s"),
            PhonemeObservationModel("θ", 4, 10, 6, "s"),
            PhonemeObservationModel("θ", 5, 10, 8, "s"),
        )
        val improving = ErrorPatternDetector.detectOne(history, "θ")!!
        val flat = ErrorPatternDetector.detectOne(steady, "θ")!!
        assertTrue(improving.confidence < flat.confidence, "improvement must lower confidence")
    }

    @Test
    fun `persistent only reports the confident ones`() {
        val history = listOf(miss(1), miss(2), miss(3))
        assertTrue(ErrorPatternDetector.persistent(history).isNotEmpty())
    }
}
