package com.glosso.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.glosso.app.designsystem.GlossoTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The production artwork component on a real device, fed a real SVG (a
 * traced catalogue image) from the test assets. It proves the app loader
 * decodes it, that it replaces the brand fallback inside the fixed frame, and
 * that the frame keeps the 16:9 ratio the catalogue is authored in.
 */
@RunWith(AndroidJUnit4::class)
class GlossoArtworkTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().context

    // What GlossoApplication installs as Coil's singleton in the app.
    @Before
    fun installAppImageLoader() {
        SingletonImageLoader.setUnsafe(glossoImageLoader(context))
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    @Test
    fun realSvgReplacesTheFallbackInsideTheSixteenByNineFrame() {
        val svg = ImageRequest.Builder(context).data(fixture("real-artwork.svg")).build()

        rule.setContent {
            GlossoTheme(darkTheme = true) {
                Column {
                    GlossoArtwork(svg, "svg artwork", Modifier.testTag(SVG_TAG))
                    GlossoArtwork(null, "fallback", Modifier.testTag(FALLBACK_TAG))
                }
            }
        }
        renderAndSettle()

        val svgImage = rule.onNodeWithTag(SVG_TAG).captureToImage()
        val fallbackImage = rule.onNodeWithTag(FALLBACK_TAG).captureToImage()

        val ratio = svgImage.width.toFloat() / svgImage.height
        assertTrue("the frame should stay 16:9 but was $ratio", ratio in 1.7f..1.8f)
        assertEquals(svgImage.width, fallbackImage.width)

        val diff = meanAbsDifference(svgImage, fallbackImage)
        assertTrue(
            "the SVG did not render, so the node still shows the fallback (diff=$diff)",
            diff > 0.02f,
        )
    }

    private fun renderAndSettle() {
        rule.waitForIdle()
        Thread.sleep(2_000)
        rule.waitForIdle()
    }

    private fun meanAbsDifference(a: ImageBitmap, b: ImageBitmap): Float {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var sum = 0f
        var samples = 0
        val stepX = (a.width / 16).coerceAtLeast(1)
        val stepY = (a.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                sum += channelDistance(pa[x, y], pb[x, y])
                samples++
                x += stepX
            }
            y += stepY
        }
        return if (samples == 0) 0f else sum / samples
    }

    private fun channelDistance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    private companion object {
        const val SVG_TAG = "svg_artwork"
        const val FALLBACK_TAG = "fallback_artwork"
    }
}
