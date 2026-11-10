package com.glosso.server.analytics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The events an app may send to `POST /v1/events`, and the properties each one
 * may carry (docs/ANALYTICS-BETA.md). Anything else is dropped: an unknown
 * event, a server-side name such as `score_attempt`, or a property that is not
 * declared or out of range. Properties are only booleans, bounded numbers and
 * short ids, so free text about the user can never be stored.
 */
object ClientEvents {

    sealed interface Prop {
        fun accept(value: JsonElement): JsonPrimitive?
    }

    data object Bool : Prop {
        override fun accept(value: JsonElement): JsonPrimitive? =
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
    }

    data class Count(val max: Int) : Prop {
        override fun accept(value: JsonElement): JsonPrimitive? =
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?.takeIf { it in 0..max }?.let(::JsonPrimitive)
    }

    /** A content or product id such as `ru.lesson.a1.07` or `pro_monthly`. */
    data object Id : Prop {
        override fun accept(value: JsonElement): JsonPrimitive? =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { ID.matches(it) }?.let(::JsonPrimitive)
    }

    /** Comma-separated ids, e.g. the error rules of one take. */
    data object Ids : Prop {
        override fun accept(value: JsonElement): JsonPrimitive? {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (text.isEmpty()) return JsonPrimitive("")
            val ids = text.split(',')
            return if (ids.size <= MAX_IDS && ids.all { ID.matches(it) }) JsonPrimitive(text) else null
        }
    }

    data class OneOf(val values: Set<String>) : Prop {
        override fun accept(value: JsonElement): JsonPrimitive? =
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content in values }
    }

    private val ID = Regex("[A-Za-z0-9._:-]{1,64}")
    private const val MAX_IDS = 20
    private val CEFR = OneOf(setOf("A1", "A2", "B1", "B2", "C1", "C2"))
    private val SCORE_BAND = Count(100)
    private const val DAY_SECONDS = 86_400

    /** The app's screens and sheets, as `screen_view` names them. */
    val SCREENS = setOf(
        "today", "lessons", "you", "library", "lesson", "practice", "stories", "story", "word",
        "exercises", "offline", "settings", "about", "privacy", "licenses", "recovery", "paywall",
        "onboarding", "level_picker", "download_sheet",
    )
    private val SCREEN = OneOf(SCREENS)
    private val LESSON_STEP = OneOf(setOf("review", "context", "base", "exercises", "speak", "drills", "summary"))
    private val DOWNLOAD_KIND = OneOf(setOf("lesson", "model"))

    val ERROR_KINDS = setOf(
        "score_failed", "score_offline", "not_evaluated", "mic_failed", "content_failed", "audio_failed",
    )

    val catalogue: Map<String, Map<String, Prop>> = mapOf(
        // Context without identifying the device: no model, brand or hardware id.
        "app_open" to mapOf(
            "cold" to Bool,
            "android_sdk" to Count(100),
            "tablet" to Bool,
            "dark_mode" to Bool,
            "ondevice_model" to Bool,
            "ui_language" to Id,
            "accent" to Id,
        ),
        "session_start" to emptyMap(),
        "session_end" to mapOf("duration_s" to Count(DAY_SECONDS), "exercises" to Count(10_000)),
        "onboarding_started" to emptyMap(),
        "onboarding_completed" to mapOf("steps" to Count(50), "skipped" to Bool),
        "lesson_started" to mapOf("lesson_id" to Id, "cefr" to CEFR),
        "lesson_completed" to mapOf("lesson_id" to Id, "cefr" to CEFR, "duration_s" to Count(DAY_SECONDS)),
        "exercise_completed" to mapOf(
            "kind" to Id,
            "score_band" to SCORE_BAND,
            "mastery" to Bool,
            "latency_ms" to Count(120_000),
            "on_device" to Bool,
        ),
        "pronunciation_attempt" to mapOf(
            "mode" to Id,
            "score_band" to SCORE_BAND,
            "mastery" to Bool,
            "errors" to Ids,
        ),
        "flow_abandoned" to mapOf("flow" to Id, "step" to Id, "reason" to Id),
        "mic_permission_denied" to mapOf("flow" to Id),
        "paywall_viewed" to mapOf("source" to Id),
        "pro_cta_tapped" to mapOf("source" to Id),
        "purchase_started" to mapOf("product_id" to Id, "plan" to Id),
        "purchase_failed" to mapOf("product_id" to Id, "reason" to Id),
        "screen_view" to mapOf("screen" to SCREEN),
        "lesson_step" to mapOf("lesson_id" to Id, "step" to LESSON_STEP),
        "audio_played" to mapOf(
            "source" to OneOf(setOf("drill", "line", "partner", "piece", "word", "story", "example", "take")),
            "speed" to OneOf(setOf("normal", "slow")),
        ),
        "recall_answered" to mapOf(
            "verdict" to OneOf(setOf("recalled", "partial", "not_that_phrase", "revealed")),
            "step" to OneOf(setOf("review", "speak")),
        ),
        "story_opened" to mapOf("story_id" to Id, "cefr" to CEFR),
        "story_closed" to mapOf("story_id" to Id, "read_pct" to Count(100)),
        "setting_changed" to mapOf(
            "setting" to OneOf(
                setOf("target_language", "accent", "ui_language", "phonetics", "translations", "reminder", "wifi_only"),
            ),
            "value" to Id,
            "enabled" to Bool,
        ),
        "download_started" to mapOf("kind" to DOWNLOAD_KIND, "lesson_id" to Id, "size_mb" to Count(5_000)),
        "download_completed" to mapOf("kind" to DOWNLOAD_KIND, "lesson_id" to Id, "size_mb" to Count(5_000)),
        "download_failed" to mapOf("kind" to DOWNLOAD_KIND, "lesson_id" to Id, "reason" to Id),
        "model_deleted" to emptyMap(),
        "app_error" to mapOf(
            "kind" to OneOf(ERROR_KINDS),
            "screen" to SCREEN,
            "exception" to Id,
            "where" to Id,
            "trace_id" to Id,
        ),
        // The exception's type and the first app frame, never its message: a
        // message can carry anything, a class and method name cannot.
        "app_crash" to mapOf("exception" to Id, "where" to Id, "fatal" to Bool, "trace_id" to Id),
        "notification_permission" to mapOf("granted" to Bool),
        "sign_in" to mapOf(
            "result" to OneOf(setOf("success", "cancelled", "no_account", "failed", "server_error")),
        ),
        "streak_extended" to mapOf("days" to Count(3_650)),
    )

    /**
     * The event's properties reduced to what the catalogue allows, or null when
     * the event itself is not one a client may send.
     */
    fun sanitize(name: String, properties: JsonObject): JsonObject? {
        val spec = catalogue[name] ?: return null
        return JsonObject(
            properties.mapNotNull { (key, value) ->
                spec[key]?.accept(value)?.let { key to it }
            }.toMap(),
        )
    }
}
