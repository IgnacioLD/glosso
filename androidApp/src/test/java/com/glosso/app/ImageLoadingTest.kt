package com.glosso.app

import android.os.Looper
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.svg.SvgDecoder
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one image loader every screen resolves has to register SVG decoding and
 * decode both formats the catalogue can carry, with no network and no device.
 *
 * The SVG fixture gives width/height (160x90) a different ratio from its
 * viewBox (320x100, i.e. 3.2:1), so a passing test proves the viewBox drives
 * the intrinsic aspect and a requested size is fitted to it, never stretched.
 *
 * Coil hands the result back through the main dispatcher, so the request runs
 * on a worker while the test's main thread pumps the paused looper; blocking
 * inside `runBlocking` on the main thread itself would deadlock. The SVG tests
 * pin the decoder explicitly because Robolectric's shadow `BitmapFactory`
 * happily returns a fake bitmap for any bytes, which would otherwise win the
 * decoder race before `SvgDecoder` is reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageLoadingTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    private fun load(data: ByteArray, size: Size? = null, svg: Boolean = false): ImageResult {
        val request = ImageRequest.Builder(context)
            .data(data)
            .apply {
                if (size != null) this.size(size)
                if (svg) decoderFactory(SvgDecoder.Factory())
            }
            .build()
        return awaitOnMain { glossoImageLoader(context).execute(request) }
    }

    private fun <T> awaitOnMain(block: suspend () -> T): T {
        val worker = Executors.newSingleThreadExecutor()
        val future = worker.submit<T> { runBlocking { block() } }
        while (!future.isDone) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        worker.shutdown()
        return future.get()
    }

    private fun aspect(image: Image): Float = image.width.toFloat() / image.height

    @Test
    fun `the app loader registers the SVG decoder`() {
        val factories = glossoImageLoader(context).components.decoderFactories
        assertTrue(
            "the app ImageLoader must include SvgDecoder.Factory, was $factories",
            factories.any { it is SvgDecoder.Factory },
        )
    }

    @Test
    fun `the app loader fetches remote images over the network`() {
        val fetchers = glossoImageLoader(context).components.fetcherFactories.map { it.first }
        assertTrue(
            "the app ImageLoader must include the network fetcher, was $fetchers",
            fetchers.any { it::class.simpleName == "Factory" && it::class.qualifiedName.orEmpty().contains("NetworkFetcher") },
        )
    }

    @Test
    fun `the app loader decodes an SVG`() {
        val result = load(fixture("artwork.svg"), svg = true)
        assertTrue("the SVG should decode, was $result", result is SuccessResult)
    }

    @Test
    fun `an SVG takes its aspect ratio from the viewBox, not from width and height`() {
        val image = (load(fixture("artwork.svg"), svg = true) as SuccessResult).image
        assertEquals("viewBox aspect 320:100", 3.2f, aspect(image), 0.02f)
    }

    @Test
    fun `an SVG scales to the requested size keeping its viewBox aspect ratio`() {
        val image = (load(fixture("artwork.svg"), Size(640, 360), svg = true) as SuccessResult).image
        assertEquals("fitted to the requested width", 640, image.width)
        assertEquals("viewBox aspect kept inside the request", 3.2f, aspect(image), 0.02f)
    }

    @Test
    fun `the same loader still decodes a PNG`() {
        val result = load(fixture("artwork.png"))
        assertTrue("the PNG should decode, was $result", result is SuccessResult)
        val image = (result as SuccessResult).image
        assertEquals(32, image.width)
        assertEquals(18, image.height)
    }
}
