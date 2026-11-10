package com.glosso.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.glosso.app.designsystem.GlossoTheme
import com.glosso.app.resources.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The meaning of the phrase is a crutch, so it starts hidden and only appears
 * when the learner asks for it. The card must respect the preference both ways:
 * nothing under the phrase by default, the meaning after the quiet reveal, and
 * gone again once hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationRevealTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theMeaningIsHiddenByDefaultAndAppearsOnReveal() {
        val context = RuntimeEnvironment.getApplication()
        TranslationPreferences.setVisible(context, false)
        val meaning = "El sendero pasa junto a la puerta norte."

        rule.setContent {
            GlossoTheme(darkTheme = true) {
                DrillCard(
                    text = "The path runs by the north gate.",
                    ipa = "ðə pɑːθ rʌnz baɪ ðə nɔːθ ɡeɪt",
                    translation = meaning,
                    focus = emptyList(),
                )
            }
        }

        rule.onNodeWithText(meaning).assertDoesNotExist()

        rule.onNodeWithText(LocalizedStrings(Locale.getDefault()).getString(Res.string.translation_show)).performClick()
        rule.onNodeWithText(meaning).assertIsDisplayed()

        rule.onNodeWithText(LocalizedStrings(Locale.getDefault()).getString(Res.string.translation_hide)).performClick()
        rule.onNodeWithText(meaning).assertDoesNotExist()
    }
}
