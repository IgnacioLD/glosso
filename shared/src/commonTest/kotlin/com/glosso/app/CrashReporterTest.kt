package com.glosso.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CrashReporterTest {

    private val frames = listOf(
        CrashFrame("java.util.ArrayList", "get"),
        CrashFrame("com.glosso.app.LessonViewModel\$stepTo\$1", "invoke"),
    )

    @Test
    fun `a crash keeps the type and the first app frame`() {
        assertEquals(
            StoredCrash("IllegalStateException", "LessonViewModel_stepTo_1.invoke"),
            CrashReporter.describe("IllegalStateException", frames),
        )
    }

    @Test
    fun `the message is never part of what is stored`() {
        val secret = "user typed: hola maria"
        val stored = CrashReporter.describe(IllegalStateException(secret))
        assertFalse(stored.toString().contains("hola"))
        assertFalse(stored.toString().contains("typed"))
        assertEquals("IllegalStateException", stored.exception)
    }

    @Test
    fun `without a type or frames the crash still has ids`() {
        assertEquals(StoredCrash("Throwable", "unknown"), CrashReporter.describe("", emptyList()))
    }

    @Test
    fun `without an app frame the top frame is used`() {
        assertEquals("ArrayList.get", CrashReporter.describe("X", frames.take(1)).where)
    }

    @Test
    fun `native stack lines become frames`() {
        assertEquals(
            CrashFrame("com.glosso.app.LessonViewModel", "stepTo", fileName = null, line = -1),
            CrashReporter.parseNativeFrame(
                "3   shared   0x0000000104f2a1b8 kfun:com.glosso.app.LessonViewModel#stepTo(kotlin.Int){} + 120",
            ),
        )
        assertEquals(
            CrashFrame("com.glosso.app.LessonViewModel.stepTo\$lambda\$0", "invoke", fileName = null, line = -1),
            CrashReporter.parseNativeFrame("kfun:com.glosso.app.LessonViewModel.stepTo\$lambda\$0#invoke(){}"),
        )
        assertNull(CrashReporter.parseNativeFrame("7   libsystem_kernel.dylib   0x00000001 __pthread_kill + 8"))
    }

    @Test
    fun `buildTrace captures cause chain and limits to 4 causes and 64 frames`() {
        val bottom = IllegalArgumentException("bottom secret")
        val middle = RuntimeException("middle secret", bottom)
        val top = IllegalStateException("top secret", middle)

        val trace = CrashReporter.buildTrace(top, "tr-1234", fatal = true)
        assertEquals("tr-1234", trace.traceId)
        assertEquals(true, trace.fatal)
        assertEquals("crash", trace.kind)
        assertEquals("IllegalStateException", trace.exception)
        assertEquals(3, trace.causes.size)
        assertEquals("IllegalStateException", trace.causes[0].type)
        assertEquals("RuntimeException", trace.causes[1].type)
        assertEquals("IllegalArgumentException", trace.causes[2].type)

        // Verify no secret message leaked into trace
        assertFalse(trace.toString().contains("secret"))
    }
}
