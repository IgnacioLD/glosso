package com.glosso.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guard for the "nothing advances on its own" invariant. A feedback screen
 * that auto-advances after a fixed delay (exercise verdict, lesson drill score)
 * deletes the value of the exercise before the learner can read it, which is the
 * bug this test pins down. It is a source audit, like [ContentGateTest]: it reads
 * the production sources and fails if a timer creeps back into the exercise
 * runner or the lesson drills step, or if the explicit continue action is
 * removed. It does not exercise the composables.
 */
class NoAutoAdvanceTest {

    private fun mainSourceDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val moduleRelative = File(dir, "src/commonMain/kotlin/com/glosso/app")
            if (moduleRelative.isDirectory) return moduleRelative
            val repoRelative = File(dir, "shared/src/commonMain/kotlin/com/glosso/app")
            if (repoRelative.isDirectory) return repoRelative
            dir = dir.parentFile
        }
        error("shared common sources not found from ${File(".").absolutePath}")
    }

    private fun source(name: String): File =
        mainSourceDir().walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: error("expected production file $name")

    /** The text of a top-level function, from its declaration to the next one. */
    private fun functionBody(file: File, marker: String): String {
        val text = file.readText()
        val start = text.indexOf(marker)
        require(start >= 0) { "$marker not found in ${file.name}" }
        val next = text.indexOf("\nprivate fun ", start + marker.length)
        return if (next >= 0) text.substring(start, next) else text.substring(start)
    }

    @Test
    fun `the exercise runner never advances on a timer`() {
        val runner = functionBody(source("ExerciseSession.kt"), "internal fun ExerciseRunner(")
        assertFalse(
            "ExerciseRunner must not delay; the learner taps Continue",
            runner.contains("delay("),
        )
        assertTrue(
            "ExerciseRunner must offer the explicit Continue action",
            runner.contains("Res.string.exercise_continue") && runner.contains(".advance()"),
        )
    }

    @Test
    fun `the lesson drills step never advances on a timer`() {
        val drills = functionBody(source("LessonsScreen.kt"), "private fun DrillsStep(")
        assertFalse(
            "DrillsStep must not delay; the learner taps the next action",
            drills.contains("delay("),
        )
        assertTrue(
            "DrillsStep must offer the explicit next action",
            drills.contains("Res.string.practice_next"),
        )
    }

    @Test
    fun `free practice offers a next action instead of only skip`() {
        val practice = functionBody(source("GlossoApp.kt"), "internal fun PracticeScreen(")
        assertTrue(
            "PracticeScreen must offer the explicit next action",
            practice.contains("Res.string.practice_next"),
        )
    }
}
