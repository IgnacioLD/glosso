package com.glosso.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.glosso.app.designsystem.GlossoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shared bottom action bar: the one place the primary action of a flow
 * lives. These tests pin that it shows and runs its content, and that the
 * embedded case (navigation inset off, where the surrounding scroll already
 * applies it) still renders its content.
 */
class BottomActionBarTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun showsItsContent() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                BottomActionBar {
                    PrimaryButton(text = "Continuar", onClick = {})
                }
            }
        }

        rule.onNodeWithText("Continuar").assertIsDisplayed()
    }

    @Test
    fun theActionReceivesClicks() {
        var clicked = false
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                BottomActionBar {
                    PrimaryButton(text = "Continuar", onClick = { clicked = true })
                }
            }
        }

        rule.onNodeWithText("Continuar").performClick()

        assertTrue("the bar's action should receive the click", clicked)
    }

    @Test
    fun contentIsShownWithoutTheNavigationInset() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                BottomActionBar(navigationInset = false) {
                    Text("Sin inset", modifier = Modifier.testTag(TAG).fillMaxWidth())
                }
            }
        }

        rule.onNodeWithTag(TAG).assertIsDisplayed()
    }

    private companion object {
        const val TAG = "bottom_bar_content"
    }
}
