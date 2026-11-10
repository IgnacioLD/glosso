package com.glosso.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The asset route's range maths: a resumed download depends on the server
 * handing back exactly the requested slice, and on falling back to the whole
 * file when the request is unsatisfiable or the file changed.
 */
class AssetRangeTest {

    @Test
    fun `a bounded range is honoured`() {
        val range = parseByteRange("bytes=100-199", total = 1000, ifRange = null, etag = "\"e\"")
        assertEquals(100L, range?.start)
        assertEquals(199L, range?.endInclusive)
    }

    @Test
    fun `an open-ended range runs to the last byte`() {
        val range = parseByteRange("bytes=500-", total = 1000, ifRange = null, etag = "\"e\"")
        assertEquals(500L, range?.start)
        assertEquals(999L, range?.endInclusive)
    }

    @Test
    fun `an end past the file is clamped`() {
        val range = parseByteRange("bytes=990-5000", total = 1000, ifRange = null, etag = "\"e\"")
        assertEquals(990L, range?.start)
        assertEquals(999L, range?.endInclusive)
    }

    @Test
    fun `no range header means the whole file`() {
        assertNull(parseByteRange(null, total = 1000, ifRange = null, etag = "\"e\""))
    }

    @Test
    fun `a start past the end is unsatisfiable`() {
        assertNull(parseByteRange("bytes=1000-1200", total = 1000, ifRange = null, etag = "\"e\""))
    }

    @Test
    fun `a changed etag ignores the range so the client restarts`() {
        assertNull(parseByteRange("bytes=10-20", total = 1000, ifRange = "\"old\"", etag = "\"new\""))
    }

    @Test
    fun `a matching etag keeps the range`() {
        val range = parseByteRange("bytes=10-20", total = 1000, ifRange = "\"same\"", etag = "\"same\"")
        assertEquals(10L, range?.start)
        assertEquals(20L, range?.endInclusive)
    }
}
