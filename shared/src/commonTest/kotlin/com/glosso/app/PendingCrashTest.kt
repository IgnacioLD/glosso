package com.glosso.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PendingCrashTest {

    @Test
    fun `stored values are sanitised to short ids`() {
        assertEquals("a_b_c", PendingCrash.sanitize("a b/c"))
        assertEquals("LessonViewModel.stepTo:1-x", PendingCrash.sanitize("LessonViewModel.stepTo:1-x"))
        assertEquals(64, PendingCrash.sanitize("x".repeat(200)).length)
    }

    @Test
    fun `frame values are sanitised to code identifiers up to 256 chars`() {
        assertEquals("LessonViewModel\$stepTo\$1", PendingCrash.sanitizeFrame("LessonViewModel\$stepTo\$1"))
        assertEquals("a_b_c", PendingCrash.sanitizeFrame("a b/c"))
        assertEquals(256, PendingCrash.sanitizeFrame("x".repeat(300)).length)
    }
}
