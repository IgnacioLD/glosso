package com.glosso.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The regression guard for the gate. Issue #42 shipped a CTA that opened a
 * lesson without asking [com.glosso.app.billing.AccessController], so this test
 * reads the production sources and fails if a content-opening site stops going
 * through the one gate, if the entitlement starts being decided outside the
 * billing package, or if the free frontier is re-derived on a screen.
 *
 * It is a source audit, like [LicensesTest]: the point is to catch the next
 * screen that "just opens" content, not to exercise the composables.
 */
class ContentGateTest {

    /** The app's production sources: the Android app and the shared (multiplatform) code. */
    private fun mainSourceDirs(): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val app = File(dir, "androidApp/src/main/java/com/glosso/app")
            val shared = File(dir, "shared/src/commonMain/kotlin/com/glosso/app")
            if (app.isDirectory && shared.isDirectory) return listOf(app, shared)
            dir = dir.parentFile
        }
        error("app sources not found from ${File(".").absolutePath}")
    }

    private fun kotlinFiles(): List<File> =
        mainSourceDirs().flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun File.isBilling(): Boolean =
        path.replace('\\', '/').contains("/billing/")

    /**
     * The calls that actually start content for the learner. The `(?<!fun )`
     * keeps the composable/function definitions out: only a call site can skip
     * the gate.
     */
    private val contentOpeners = listOf(
        Regex("""\.openLesson\("""),
        Regex("""\.openStory\("""),
        Regex("""\bvm\.resume\(\)"""),
        Regex("""\blessonVm\.resume\(\)"""),
        Regex("""(?<!fun )ExerciseRunner\("""),
        Regex("""(?<!fun )PracticeScreen\("""),
    )

    @Test
    fun `every content-opening file goes through AccessController`() {
        val offenders = kotlinFiles()
            .filterNot { it.isBilling() }
            .filter { file -> contentOpeners.any { it.containsMatchIn(file.readText()) } }
            .filterNot { it.readText().contains("AccessController") }

        assertTrue(
            "content is opened without AccessController in: " +
                offenders.joinToString { it.name },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the Pro surfaces each name the gate they use`() {
        // Pins the four surfaces F7 protects plus the shell, so removing the gate
        // from one of them is a red build, not a silent regression.
        val expectations = mapOf(
            "LessonsScreen.kt" to "AccessController",
            "StoriesScreen.kt" to "AccessController",
            "ExercisesScreen.kt" to "AccessController",
            "GlossoApp.kt" to "AccessController",
            "PracticeViewModel.kt" to "AccessController",
            "YouScreen.kt" to "ProFeature.PHONETIC_PROFILE",
            // Today shows the sounds gate as a quiet locked row (no second gold
            // button), so the marker is the short locked line it renders, not the
            // full ProLockedCard.
            "TodayScreen.kt" to "today_sounds_locked",
        )
        expectations.forEach { (name, marker) ->
            val file = kotlinFiles().firstOrNull { it.name == name }
                ?: error("expected production file $name")
            assertTrue("$name must gate through $marker", file.readText().contains(marker))
        }
    }

    @Test
    fun `the entitlement predicate is only read inside billing`() {
        // `AccessPolicy.isEntitled` is the gate's own function and is allowed; a
        // raw `<status>.isEntitled` on a screen is not.
        val directUse = Regex("""(?<!AccessPolicy)\.isEntitled""")
        val offenders = kotlinFiles()
            .filterNot { it.isBilling() }
            .filter { directUse.containsMatchIn(it.readText()) }

        assertTrue(
            "entitlement is decided outside billing/: " + offenders.joinToString { it.name },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the free frontier is not hardcoded outside AccessPolicy`() {
        // Reading `AccessPolicy.FREE_LEVEL` is fine (it is the policy); comparing
        // against a level literal, or naming the old block constant, is a screen
        // re-deriving the frontier and is not.
        val hardcoded = Regex("""(==|!=)\s*"A[12]"|"A[12]"\s*(==|!=)|FREE_BLOCKS""")
        val offenders = kotlinFiles()
            .filterNot { it.isBilling() }
            .filter { hardcoded.containsMatchIn(it.readText()) }

        assertTrue(
            "the free frontier is re-derived outside AccessPolicy in: " +
                offenders.joinToString { it.name },
            offenders.isEmpty(),
        )
    }
}
