package com.glosso.server.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The catalogue is the privacy boundary: what is not declared here never reaches
 * the database, whatever an app version sends.
 */
class ClientEventsTest {

    @Test
    fun `declared properties pass and everything else is dropped`() {
        val kept = ClientEvents.sanitize(
            "app_open",
            buildJsonObject {
                put("cold", JsonPrimitive(true))
                put("android_sdk", JsonPrimitive(35))
                put("tablet", JsonPrimitive(false))
                put("ui_language", JsonPrimitive("es"))
                put("device_model", JsonPrimitive("SM-S918B"))
            },
        )

        assertEquals(setOf("cold", "android_sdk", "tablet", "ui_language"), kept?.keys)
    }

    @Test
    fun `a screen outside the list is not a screen`() {
        assertEquals(
            "settings",
            ClientEvents.sanitize("screen_view", buildJsonObject { put("screen", JsonPrimitive("settings")) })
                ?.get("screen")?.let { (it as JsonPrimitive).content },
        )
        assertEquals(
            emptySet(),
            ClientEvents.sanitize("screen_view", buildJsonObject { put("screen", JsonPrimitive("my notes")) })?.keys,
        )
    }

    @Test
    fun `a crash keeps its type and frame but never a message`() {
        val kept = ClientEvents.sanitize(
            "app_crash",
            buildJsonObject {
                put("exception", JsonPrimitive("IllegalStateException"))
                put("where", JsonPrimitive("LessonViewModel.stepTo"))
                put("fatal", JsonPrimitive(true))
                put("trace_id", JsonPrimitive("tr-12345"))
                put("message", JsonPrimitive("user typed hello"))
            },
        )
        assertEquals(setOf("exception", "where", "fatal", "trace_id"), kept?.keys)
        assertEquals("tr-12345", (kept?.get("trace_id") as? JsonPrimitive)?.content)

        val withText = ClientEvents.sanitize(
            "app_crash",
            buildJsonObject { put("exception", JsonPrimitive("Failed for maria@example.com")) },
        )
        assertEquals(emptySet(), withText?.keys, "text with spaces or @ is not an id")
    }

    @Test
    fun `an app error accepts exception, where and trace_id as optional ids`() {
        val kept = ClientEvents.sanitize(
            "app_error",
            buildJsonObject {
                put("kind", JsonPrimitive("score_failed"))
                put("screen", JsonPrimitive("practice"))
                put("exception", JsonPrimitive("TimeoutException"))
                put("where", JsonPrimitive("Scorer.score"))
                put("trace_id", JsonPrimitive("tr-98765"))
                put("message", JsonPrimitive("sensitive details"))
                put("unknown_field", JsonPrimitive("drop"))
            },
        )
        assertEquals(setOf("kind", "screen", "exception", "where", "trace_id"), kept?.keys)
        assertEquals("TimeoutException", (kept?.get("exception") as? JsonPrimitive)?.content)
        assertEquals("Scorer.score", (kept?.get("where") as? JsonPrimitive)?.content)
        assertEquals("tr-98765", (kept?.get("trace_id") as? JsonPrimitive)?.content)
    }

    @Test
    fun `server-side names cannot be sent by an app`() {
        assertNull(ClientEvents.sanitize("score_attempt", buildJsonObject {}))
        assertNull(ClientEvents.sanitize("theme_suggested", buildJsonObject {}))
    }
}
