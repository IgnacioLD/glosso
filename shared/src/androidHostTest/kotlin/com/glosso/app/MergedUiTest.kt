package com.glosso.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.glosso.app.designsystem.GlossoTheme
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The accessibility and state contract of the merged shell and recorder: tabs
 * that TalkBack announces as tabs, a deck whose controls enable only when they
 * can act, a score read once, and a collapsing header that names the page once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergedUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun str(res: StringResource, vararg args: Any) = LocalizedStrings(Locale.getDefault()).getString(res, *args)

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Test
    fun `the bottom bar is four labelled tabs with one selected`() {
        var picked: GlossoDestination? = null
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                GlossoBottomBar(current = GlossoDestination.TODAY, onSelect = { picked = it })
            }
        }

        rule.onAllNodes(isTab).assertCountEquals(GlossoDestination.entries.size)
        val today = rule.onNode(isTab and hasText(str(GlossoDestination.TODAY.labelRes)))
        today.assertIsSelected()
        rule.onNode(isTab and hasText(str(GlossoDestination.LIBRARY.labelRes)))
            .assertIsNotSelected()
            .performClick()
        assertEquals(GlossoDestination.LIBRARY, picked)
    }

    @Test
    fun `the deck plays the own take only once there is one`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                RecordingDeck(
                    state = deckState(AttemptState(), mapOf("en-US:normal" to "a.mp3"), playing = false),
                    level = { 0f },
                    onToggle = {},
                    onPlay = {},
                    onPlayTake = {},
                )
            }
        }

        rule.onNodeWithText(str(Res.string.practice_normal)).assertIsEnabled()
        rule.onNodeWithText(str(Res.string.practice_your_take)).assertIsNotEnabled()
        // No rendered slow clip: the normal one is offered slowed by rate.
        rule.onNodeWithText(str(Res.string.practice_slow)).assertIsEnabled()
        rule.onNode(hasContentDescription(str(Res.string.practice_start_recording))).assertIsEnabled()
    }

    @Test
    fun `while recording the references wait and the mic offers to stop`() {
        var played = false
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                RecordingDeck(
                    state = deckState(
                        AttemptState(recording = true, takePath = "/cache/take.wav"),
                        mapOf("en-US:normal" to "a.mp3", "en-US:slow" to "b.mp3"),
                        playing = false,
                    ),
                    level = { 0.5f },
                    onToggle = {},
                    onPlay = { played = true },
                    onPlayTake = {},
                )
            }
        }

        rule.onNodeWithText(str(Res.string.practice_slow)).assertIsNotEnabled()
        rule.onNodeWithText(str(Res.string.practice_your_take)).assertIsNotEnabled()
        rule.onNodeWithContentDescription(str(Res.string.practice_stop_score)).assertIsEnabled()
        assertEquals(false, played)
    }

    @Test
    fun `a score ring is read once as its final score`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) { ScoreRing(score = 72) }
        }

        rule.onNodeWithContentDescription(str(Res.string.score_a11y, 72)).assertExists()
    }

    @Test
    fun `the collapsing header names the page once for talkback`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                TopHeader(title = "Lessons", subtitle = "Your course", collapse = { 1f })
            }
        }

        rule.onAllNodesWithText("Lessons").assertCountEquals(1)
        rule.onNode(hasText("Lessons") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }
}
