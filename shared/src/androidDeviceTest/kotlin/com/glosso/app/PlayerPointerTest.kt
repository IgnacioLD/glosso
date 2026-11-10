package com.glosso.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The lesson player is an overlay over the lesson list. A tap on one of its
 * non-interactive areas must not reach the list behind it. These two tests pin
 * the contract: the guard swallows the tap, and without it the tap leaks.
 */
class PlayerPointerTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun playerSurfaceSwallowsTapsOverTheListBehind() {
        var behindTaps = 0
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(BEHIND)
                        .clickable { behindTaps++ },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(PLAYER)
                        .background(Color.Black)
                        .consumePointerEvents(),
                )
            }
        }

        rule.onNodeWithTag(PLAYER).performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("the tap fell through the player to the list behind", 0, behindTaps)
    }

    @Test
    fun withoutTheGuardTheTapLeaksToTheListBehind() {
        var behindTaps = 0
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(BEHIND)
                        .clickable { behindTaps++ },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(PLAYER)
                        .background(Color.Black),
                )
            }
        }

        rule.onNodeWithTag(PLAYER).performTouchInput { click() }
        rule.waitForIdle()

        assertEquals("an unguarded overlay should leak the tap", 1, behindTaps)
    }

    private companion object {
        const val BEHIND = "behind"
        const val PLAYER = "player"
    }
}
