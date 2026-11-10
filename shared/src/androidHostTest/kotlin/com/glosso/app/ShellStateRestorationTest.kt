package com.glosso.app

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell state goes through Android's real saved-instance path (a Bundle),
 * which is what a rotation and a process death use: a value the Bundle cannot
 * hold would throw here instead of on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellStateRestorationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the shell comes back from saved instance state`() {
        val tester = StateRestorationTester(compose)
        var setShell: (ShellState) -> Unit = {}
        tester.setContent {
            var shell by rememberSaveable(stateSaver = ShellStateSaver) { mutableStateOf(ShellState()) }
            setShell = { shell = it }
            Text("${shell.destination}/${shell.route}/${shell.openLessonId}/${shell.openStoryId}")
        }
        compose.onNodeWithText("TODAY/null/null/null").assertIsDisplayed()

        compose.runOnIdle {
            setShell(ShellState(GlossoDestination.LIBRARY, LibraryRoute.STORIES, openStoryId = "s1"))
        }
        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("LIBRARY/STORIES/null/s1").assertIsDisplayed()
    }
}
