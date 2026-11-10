package com.glosso.server.accent

import com.glosso.engine.accent.AccentCatalog
import com.glosso.engine.accent.AccentSystem
import com.glosso.engine.model.VoiceGender
import com.glosso.server.api.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Multi-accent scoring switch. Off by default: the systems are seeds until each
 * accent's native audio is measured (docs/MULTI-ACCENT.md). `GLOSSO_MULTI_ACCENT=1`
 * turns it on for English in `/v1/score`.
 */
data class MultiAccentConfig(val enabled: Boolean) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): MultiAccentConfig =
            MultiAccentConfig(env("GLOSSO_MULTI_ACCENT")?.trim()?.lowercase() in setOf("1", "true", "on"))
    }
}

@Serializable
data class AccentSystemDto(
    val id: String,
    val accent: String,
    val label: String,
    val gender: VoiceGender,
    val rhotic: Boolean,
    val rules: List<String>,
    val calibrated: Boolean,
    val voices: Int,
)

@Serializable
data class AccentCatalogResponse(val multiAccentScoring: Boolean, val systems: List<AccentSystemDto>)

@Serializable
data class RegisterVoiceRequest(
    val voiceId: String,
    val accent: String,
    val gender: String,
    val label: String? = null,
    /** Inline audio; when absent the configured fetcher is asked for it. */
    val audioBase64: String? = null,
)

@Serializable
data class RegisterVoiceResponse(val voice: RegisteredVoice, val catalogued: Boolean)

@Serializable
data class CatalogVoiceDto(
    val voiceId: String,
    val accent: String,
    val gender: VoiceGender,
    val label: String,
    val system: String,
    val registered: Boolean,
    val sha256: String? = null,
)

@Serializable
data class VoiceListResponse(
    val fetcher: String,
    val catalogue: List<CatalogVoiceDto>,
    /** Registered voices the catalogue does not list yet (they join no system). */
    val uncatalogued: List<RegisteredVoice>,
)

@Serializable
data class VoiceErrorResponse(val error: String, val reason: String)

/**
 * `GET /accents` (public): the accent systems and whether multi-accent scoring is on.
 * `GET /admin/voices`, `POST /admin/voices` (admin): the catalogue's voices with
 * their registration state, and the ingest of one voice.
 */
fun Route.accentRoutes(
    config: MultiAccentConfig,
    ingest: VoiceIngestService,
    isAdmin: ApplicationCall.() -> Boolean,
) {
    get("/accents") {
        call.respond(
            AccentCatalogResponse(
                multiAccentScoring = config.enabled,
                systems = AccentCatalog.ENGLISH.map { system ->
                    AccentSystemDto(
                        id = system.id,
                        accent = system.accent,
                        label = system.label,
                        gender = system.gender,
                        rhotic = system.phonology.rhotic,
                        rules = system.phonology.rules.map { it.id },
                        calibrated = system.phonology.calibrated,
                        voices = system.voices.size,
                    )
                },
            ),
        )
    }

    get("/admin/voices") {
        if (!call.isAdmin()) return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
        val registered = withContext(Dispatchers.IO) { ingest.registry.all() }
        val byId = registered.associateBy { it.voiceId }
        val catalogue = AccentCatalog.PROVIDER_VOICES.map { voice ->
            val stored = byId[voice.voiceId]
            CatalogVoiceDto(
                voiceId = voice.voiceId,
                accent = voice.accent,
                gender = voice.gender,
                label = voice.label,
                system = AccentSystem.systemId(voice.accent, voice.gender),
                registered = stored != null,
                sha256 = stored?.sha256,
            )
        }
        val uncatalogued = registered.filter { AccentCatalog.voice(it.voiceId) == null }
        call.respond(VoiceListResponse(ingest.fetcher.name, catalogue, uncatalogued))
    }

    post("/admin/voices") {
        if (!call.isAdmin()) return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
        val body = runCatching { call.receive<RegisterVoiceRequest>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, VoiceErrorResponse("invalid request body", "invalid_body"))
        }
        val outcome = withContext(Dispatchers.IO) {
            ingest.register(
                VoiceIngestService.Request(body.voiceId, body.accent, body.gender, body.label, body.audioBase64),
            )
        }
        when (outcome) {
            is VoiceIngestService.Outcome.Registered ->
                call.respond(HttpStatusCode.Created, RegisterVoiceResponse(outcome.voice, outcome.catalogued))
            is VoiceIngestService.Outcome.Rejected ->
                call.respond(HttpStatusCode.fromValue(outcome.status), VoiceErrorResponse(outcome.message, outcome.reason))
        }
    }
}
