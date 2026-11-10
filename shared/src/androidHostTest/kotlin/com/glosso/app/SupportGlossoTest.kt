package com.glosso.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.glosso.app.designsystem.GlossoTheme
import com.glosso.app.resources.*
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The voluntary "Support Glosso" entry only exists once a Ko-fi page does: with
 * a blank URL neither the You row nor the About section renders anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportGlossoTest {

    @get:Rule
    val rule = createComposeRule()

    private val title get() = LocalizedStrings(Locale.getDefault()).getString(Res.string.support_title)

    @Test
    fun `the entry is hidden while the Ko-fi URL is blank`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                SupportGlossoRow(url = "")
                SupportGlossoSection(onOpen = {}, url = "  ")
            }
        }
        rule.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun `the entry shows once a Ko-fi URL is set`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) { SupportGlossoRow(url = "https://ko-fi.com/example") }
        }
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun `the support copy exists in every app language, without em dashes`() {
        for (tag in listOf("en", "es", "fr", "de")) {
            val strings = LocalizedStrings(Locale.forLanguageTag(tag))
            for (key in listOf(Res.string.support_title, Res.string.support_subtitle, Res.string.support_link)) {
                val value = strings.getString(key)
                assertTrue("$tag: $key is blank", value.isNotBlank())
                assertFalse("$tag: $key uses an em dash", value.contains('\u2014'))
            }
        }
    }
}
