package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The geometry behind the reading follow: the least scroll that makes a
 * paragraph visible, and none while it already is. Pure numbers, no screen.
 */
class ReadingAutoScrollTest {

    private val viewport = 1000
    private val margin = 24

    @Test
    fun `paragraph fully inside is left alone`() {
        assertNull(readingScrollDelta(itemTop = 300, itemHeight = 200, viewport = viewport, margin = margin))
    }

    @Test
    fun `paragraph above the viewport scrolls up to the top margin`() {
        assertEquals(-124, readingScrollDelta(itemTop = -100, itemHeight = 200, viewport = viewport, margin = margin))
    }

    @Test
    fun `paragraph above but closer than the margin still scrolls to the margin`() {
        assertEquals(-4, readingScrollDelta(itemTop = 20, itemHeight = 200, viewport = viewport, margin = margin))
    }

    @Test
    fun `paragraph below the fold scrolls up just enough`() {
        assertEquals(124, readingScrollDelta(itemTop = 900, itemHeight = 200, viewport = viewport, margin = margin))
    }

    @Test
    fun `paragraph taller than the viewport aligns its top`() {
        assertEquals(-124, readingScrollDelta(itemTop = -100, itemHeight = 1600, viewport = viewport, margin = margin))
    }

    @Test
    fun `paragraph taller than the viewport with its top in view is left alone`() {
        assertNull(readingScrollDelta(itemTop = 100, itemHeight = 1600, viewport = viewport, margin = margin))
    }

    @Test
    fun `paragraph taller than the viewport below the fold aligns its top`() {
        assertEquals(1976, readingScrollDelta(itemTop = 2000, itemHeight = 1600, viewport = viewport, margin = margin))
    }

    @Test
    fun `touching the margin exactly does not scroll`() {
        assertNull(readingScrollDelta(itemTop = margin, itemHeight = viewport - 2 * margin, viewport = viewport, margin = margin))
        assertNull(readingScrollDelta(itemTop = margin + 1, itemHeight = viewport - 2 * margin - 1, viewport = viewport, margin = margin))
    }

    @Test
    fun `a paragraph further down a scrolled reading is followed by the same small step`() {
        val viewport = 1000
        val margin = 30
        // Line 5 sits 1900 px into the content and the reading is already scrolled 1000 px:
        // it is 900 px down the screen, 100 px tall, so it needs 30 px, not 930.
        val top = viewportTop(offsetInContent = 1900, scrolled = 1000)

        assertEquals(900, top)
        assertEquals(30, readingScrollDelta(top, 100, viewport, margin))
        // Already on screen: nothing moves.
        assertEquals(null, readingScrollDelta(viewportTop(1500, 1000), 100, viewport, margin))
    }
}
