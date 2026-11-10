package com.glosso.engine.phonetics

import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.StressIssueModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StressAnalyzerTest {

    private fun phone(phone: String, stressed: Boolean = false) =
        PhoneticComparator.ReferencePhone(phone, stressed)

    private fun timing(durationMs: Int, energy: Float) = PhoneTimingModel(
        phone = "x",
        startMs = 0,
        endMs = durationMs,
        confidence = 1f,
        energy = energy,
        voiced = true,
    )

    @Test
    fun `a stressed vowel shorter than the unstressed ones is flagged`() {
        // "banana": the stressed /æ/ is shorter and quieter than the schwas.
        val words = listOf(
            listOf(phone("b"), phone("ə"), phone("n"), phone("æ", true), phone("n"), phone("ə")),
        )
        val timings = listOf(
            timing(20, 0.2f),
            timing(80, 0.2f),
            timing(30, 0.05f),
            timing(40, 0.1f),
            timing(30, 0.05f),
            timing(80, 0.2f),
        )

        val report = StressAnalyzer.analyze(timings, words)
        assertTrue(report != null)
        val finding = report.findings.single()
        assertEquals(StressIssueModel.NOT_LONG_ENOUGH, finding.issue)
        assertEquals("æ", finding.phoneme)
        assertEquals(0, finding.wordIndex)
        assertEquals(80, report.medianVowelMs)
    }

    @Test
    fun `a clear stressed vowel produces no findings`() {
        val words = listOf(
            listOf(phone("b"), phone("ə"), phone("n"), phone("æ", true), phone("n"), phone("ə")),
        )
        val timings = listOf(
            timing(20, 0.2f),
            timing(60, 0.15f),
            timing(30, 0.05f),
            timing(140, 0.3f),
            timing(30, 0.05f),
            timing(60, 0.15f),
        )

        val report = StressAnalyzer.analyze(timings, words)
        assertTrue(report != null)
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun `a stressed vowel that is long but quiet is too weak`() {
        val words = listOf(
            listOf(phone("b"), phone("ə"), phone("n"), phone("æ", true), phone("n"), phone("ə")),
        )
        val timings = listOf(
            timing(20, 0.2f),
            timing(80, 0.25f),
            timing(30, 0.05f),
            timing(130, 0.1f),
            timing(30, 0.05f),
            timing(80, 0.25f),
        )

        val report = StressAnalyzer.analyze(timings, words)
        assertTrue(report != null)
        assertEquals(StressIssueModel.TOO_WEAK, report.findings.single().issue)
    }

    @Test
    fun `the word index points at the word with the weak stress`() {
        val words = listOf(
            listOf(phone("k"), phone("æ", true), phone("t")),
            listOf(phone("b"), phone("ə"), phone("n"), phone("æ", true), phone("n"), phone("ə")),
        )
        val timings = listOf(
            timing(140, 0.3f),
            timing(120, 0.3f),
            timing(30, 0.05f),
            timing(20, 0.2f),
            timing(90, 0.25f),
            timing(30, 0.05f),
            timing(35, 0.1f),
            timing(30, 0.05f),
            timing(90, 0.25f),
        )

        val report = StressAnalyzer.analyze(timings, words)
        assertTrue(report != null)
        assertEquals(1, report.findings.single().wordIndex)
    }

    @Test
    fun `no stress marks means no stress report`() {
        val words = listOf(
            listOf(phone("b"), phone("ə"), phone("n"), phone("æ"), phone("n"), phone("ə")),
        )
        val timings = List(6) { timing(80, 0.2f) }
        assertNull(StressAnalyzer.analyze(timings, words))
    }

    @Test
    fun `too few vowels means no stress report`() {
        val words = listOf(
            listOf(phone("k"), phone("æ", true), phone("t")),
        )
        val timings = listOf(timing(120, 0.3f), timing(120, 0.3f), timing(30, 0.05f))
        assertNull(StressAnalyzer.analyze(timings, words))
    }

    @Test
    fun `timings that do not line up are ignored`() {
        val words = listOf(
            listOf(phone("b"), phone("ə"), phone("n"), phone("æ", true), phone("n"), phone("ə")),
        )
        val timings = List(3) { timing(80, 0.2f) }
        assertNull(StressAnalyzer.analyze(timings, words))
    }
}
