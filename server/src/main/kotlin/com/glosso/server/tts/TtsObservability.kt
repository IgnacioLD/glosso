package com.glosso.server.tts

import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal

/**
 * One structured observability record per meaningful step of a job. Correlated
 * by `jobId`. It never carries API keys, tokens or learner audio: the logging
 * implementation scrubs anything that looks like a secret.
 */
data class TtsEvent(
    val phase: String,
    val jobId: String? = null,
    val contentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val cacheHit: Boolean? = null,
    val attempt: Int? = null,
    val fallback: Boolean? = null,
    val errorKind: String? = null,
    val requestDurationMs: Long? = null,
    val characters: Int? = null,
    val estimatedCostUsd: BigDecimal? = null,
    val message: String? = null,
)

interface TtsObservability {
    fun record(event: TtsEvent)

    companion object {
        val NOOP: TtsObservability = object : TtsObservability {
            override fun record(event: TtsEvent) {}
        }
    }
}

/** Redacts anything that could be a credential before it reaches a log line. */
object TtsRedaction {
    private val patterns = listOf(
        Regex("sk_[A-Za-z0-9_-]{6,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]{8,}"),
        Regex("(?i)(xi-api-key\\s*[=:]\\s*)[A-Za-z0-9._-]{6,}"),
        Regex("(?i)(api[_-]?key\\s*[=:]\\s*)[A-Za-z0-9._-]{6,}"),
    )

    fun scrub(text: String): String {
        // Control characters first: a vendor error body with embedded newlines
        // must not forge extra log lines (A09 log injection).
        var out = text.filter { it.code >= 0x20 && it.code != 0x7f }
        out = patterns[0].replace(out, "***")
        out = patterns[1].replace(out, "Bearer ***")
        out = patterns[2].replace(out) { "${it.groupValues[1]}***" }
        out = patterns[3].replace(out) { "${it.groupValues[1]}***" }
        return out
    }
}

class LoggingTtsObservability : TtsObservability {
    private val logger = KotlinLogging.logger("GlossoTts")

    override fun record(event: TtsEvent) {
        val parts = buildList {
            add("phase=${event.phase}")
            event.jobId?.let { add("job=$it") }
            event.contentId?.let { add("content=$it") }
            event.provider?.let { add("provider=$it") }
            event.model?.let { add("model=$it") }
            event.voice?.let { add("voice=$it") }
            event.cacheHit?.let { add("cacheHit=$it") }
            event.attempt?.let { add("attempt=$it") }
            event.fallback?.let { add("fallback=$it") }
            event.errorKind?.let { add("error=$it") }
            event.requestDurationMs?.let { add("durationMs=$it") }
            event.characters?.let { add("chars=$it") }
            event.estimatedCostUsd?.let { add("costUsd=$it") }
            event.message?.let { add("msg=${TtsRedaction.scrub(it)}") }
        }
        logger.info { parts.joinToString(" ") }
    }
}
