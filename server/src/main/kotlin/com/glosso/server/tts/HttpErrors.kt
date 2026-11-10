package com.glosso.server.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps a vendor HTTP response to a [TtsException] with an accurate
 * [TtsFailureKind]. Only timeout, 429 and 5xx are marked transient, which is
 * what makes them eligible for retry and fallback. Everything else fails
 * immediately so a content or configuration error is never hidden by trying
 * another provider.
 */
object TtsHttpErrors {

    fun toException(
        provider: String,
        status: Int,
        response: HttpTransport.Response,
    ): TtsException {
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
        val detail = extractMessage(response.bodyText())
        val suffix = if (detail.isNullOrBlank()) "" else ": ${detail.take(200)}"
        return when {
            status == 408 -> TtsException(TtsFailureKind.TIMEOUT, "$provider request timed out$suffix")
            status == 429 -> TtsException(
                TtsFailureKind.RATE_LIMITED,
                "$provider rate limited (429)$suffix",
                retryAfterSeconds = retryAfter,
            )
            status in 500..599 -> TtsException(
                TtsFailureKind.SERVER_ERROR,
                "$provider server error ($status)$suffix",
            )
            status == 401 || status == 403 -> TtsException(
                TtsFailureKind.AUTHENTICATION,
                "$provider authentication failed ($status)$suffix",
            )
            status == 404 -> TtsException(
                TtsFailureKind.UNSUPPORTED_VOICE,
                "$provider resource not found ($status)$suffix",
            )
            status == 400 || status == 422 -> classifyBadRequest(provider, status, detail)
            else -> TtsException(TtsFailureKind.UNKNOWN, "$provider unexpected status $status$suffix")
        }
    }

    private fun classifyBadRequest(provider: String, status: Int, detail: String?): TtsException {
        val lower = detail?.lowercase().orEmpty()
        val suffix = if (detail.isNullOrBlank()) "" else ": ${detail.take(200)}"
        return when {
            "language" in lower -> TtsException(
                TtsFailureKind.UNSUPPORTED_LANGUAGE,
                "$provider rejected the language$suffix",
            )
            "voice" in lower -> TtsException(
                TtsFailureKind.UNSUPPORTED_VOICE,
                "$provider rejected the voice$suffix",
            )
            "text" in lower || "transcript" in lower -> TtsException(
                TtsFailureKind.INVALID_TEXT,
                "$provider rejected the text$suffix",
            )
            else -> TtsException(TtsFailureKind.BAD_REQUEST, "$provider bad request ($status)$suffix")
        }
    }

    /** Best-effort read of a JSON error body's message field. */
    private fun extractMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = Json.parseToJsonElement(body).jsonObject
            (obj["message"] ?: obj["detail"] ?: obj["error"])?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }
}
