package com.glosso.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProgressLineTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun fillEndsWhereTheFractionSays() {
        val fraction = 0.5f
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                Column(Modifier.width(200.dp)) {
                    ProgressLine(0f, Modifier.testTag(EMPTY_TAG))
                    Spacer(Modifier.height(8.dp))
                    ProgressLine(fraction, Modifier.testTag(HALF_TAG))
                }
            }
        }

        val track = rule.onNodeWithTag(EMPTY_TAG).captureToImage()
        val half = rule.onNodeWithTag(HALF_TAG).captureToImage()

        val fillEnd = lastDifferingColumn(track, half)
        val ratio = fillEnd.toFloat() / (track.width - 1)

        assertTrue(
            "the fill should end near $fraction of the track but ended at $ratio",
            ratio in 0.4f..0.6f,
        )
    }

    /** Last column where the filled bar differs from the empty track: the fill edge. */
    private fun lastDifferingColumn(empty: ImageBitmap, filled: ImageBitmap): Int {
        val emptyPixels = empty.toPixelMap()
        val filledPixels = filled.toPixelMap()
        val y = empty.height / 2
        for (x in empty.width - 1 downTo 0) {
            if (different(emptyPixels[x, y], filledPixels[x, y])) return x
        }
        return -1
    }

    private fun different(a: Color, b: Color): Boolean =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > 0.08f

    private companion object {
        const val EMPTY_TAG = "progress_empty"
        const val HALF_TAG = "progress_half"
    }
}
