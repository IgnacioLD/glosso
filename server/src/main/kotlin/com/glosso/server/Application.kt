package com.glosso.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import com.glosso.engine.drills.DrillBankLoader
import com.glosso.engine.drills.DrillPlanner
import com.glosso.engine.drills.DrillValidator
import com.glosso.engine.accent.AccentCatalog
import com.glosso.engine.accent.AccentScoring
import com.glosso.engine.accent.AccentTarget
import com.glosso.engine.accent.MultiAccentScorer
import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.server.accent.MultiAccentConfig
import com.glosso.server.accent.VoiceAudioFetchers
import com.glosso.server.accent.VoiceIngestService
import com.glosso.server.accent.VoiceRegistry
import com.glosso.server.accent.accentRoutes
import com.glosso.engine.g2p.G2P
import com.glosso.engine.g2p.LexiconG2P
import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.log.EngineLog
import com.glosso.engine.model.ContentManifest
import com.glosso.engine.model.DrillBank
import com.glosso.engine.model.G2PKind
import com.glosso.engine.model.LanguagePack
import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.model.PhonemeModelConfig
import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.PronunciationQualityModel
import com.glosso.engine.model.ThemeModel
import com.glosso.engine.phonetics.FeedbackAssembler
import com.glosso.engine.phonetics.ModelState
import com.glosso.engine.phonetics.PhonemeRecognizer
import com.glosso.engine.phonetics.cheap.CheapScorer
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.PhoneticProfile
import com.glosso.engine.phonetics.StressAnalyzer
import com.glosso.engine.phonetics.Wav2Vec2PhonemeRecognizer
import com.glosso.server.analytics.AnalyticsEvent
import com.glosso.server.analytics.AnalyticsMirror
import com.glosso.server.analytics.AnalyticsStore
import com.glosso.server.analytics.AnalyticsSummary
import com.glosso.server.analytics.ClientEvents
import com.glosso.server.analytics.EngagementSummary
import com.glosso.server.analytics.CrashCause
import com.glosso.server.analytics.CrashGroup
import com.glosso.server.analytics.CrashTraceRecord
import com.glosso.server.analytics.CrashTraceSanitizer
import com.glosso.server.analytics.StackFrame
import com.glosso.server.analytics.StoredCrashTrace
import com.glosso.server.analytics.UmamiMirror
import com.glosso.server.analytics.ProductSummary
import com.glosso.server.billing.BillingService
import com.glosso.server.billing.RtdnOutcome
import com.glosso.server.billing.VerifyOutcome
import com.glosso.server.product.AccountChangedConcurrently
import com.glosso.server.product.AccountSecret
import com.glosso.server.product.EntitlementService
import com.glosso.server.product.EntitlementStore
import com.glosso.server.product.GoogleIdTokenVerifier
import com.glosso.server.product.GoogleSignInConfig
import com.glosso.server.product.GoogleSignInResult
import com.glosso.server.product.JdbcEntitlementStore
import com.glosso.server.product.ProductConfig
import com.glosso.server.product.StoredEntitlement
import com.glosso.server.product.providerPlan
import com.glosso.server.product.toResponse
import com.glosso.server.content.ContentDb
import com.glosso.server.api.AlignRequest
import com.glosso.server.api.AlignResponse
import com.glosso.server.api.AnalyticsDeleteResponse
import com.glosso.server.api.AnalyticsEventDto
import com.glosso.server.api.AnalyticsIngestRequest
import com.glosso.server.api.AnalyticsIngestResponse
import com.glosso.server.api.BillingVerifyRequest
import com.glosso.server.api.BillingVerifyResponse
import com.glosso.server.api.CrashCauseDto
import com.glosso.server.api.CrashTraceDto
import com.glosso.server.api.CrashTraceResponse
import com.glosso.server.api.StackFrameDto
import com.glosso.server.api.DrillRequest
import com.glosso.server.api.ErrorResponse
import com.glosso.server.api.HealthResponse
import com.glosso.server.api.PhonemizeRequest
import com.glosso.server.api.PhonemizeResponse
import com.glosso.server.api.PubSubPushEnvelope
import com.glosso.server.api.ContentPoolItemDto
import com.glosso.server.api.ContentPoolResponse
import com.glosso.server.api.DemandAggregateResponse
import com.glosso.server.api.GenerationJobDto
import com.glosso.server.api.GenerationJobsResponse
import com.glosso.server.api.GenerationRunResponse
import com.glosso.server.api.GenerationStatusResponse
import com.glosso.server.api.NeedClassDto
import com.glosso.server.api.NeedClassListResponse
import com.glosso.server.api.NeedClassRef
import com.glosso.server.api.PhonemeSnapshotDto
import com.glosso.server.api.PoolConfigDto
import com.glosso.server.api.ProfileSyncRequest
import com.glosso.server.api.ProfileSyncResponse
import com.glosso.server.api.GoogleSignInNonceResponse
import com.glosso.server.api.GoogleSignInRequest
import com.glosso.server.api.ScoreRequest
import com.glosso.server.api.ThemeSuggestionRequest
import com.glosso.server.api.ThemeSuggestionResponse
import com.glosso.server.generation.CompositeContentCatalog
import com.glosso.server.generation.ContentCatalog
import com.glosso.server.generation.ContentPiece
import com.glosso.server.generation.ContentStatus
import com.glosso.server.generation.ControlPlaneDatabase
import com.glosso.server.generation.ControlPlaneStore
import com.glosso.server.generation.DemandScheduler
import com.glosso.server.generation.DemandService
import com.glosso.server.generation.EmptyContentCatalog
import com.glosso.server.generation.GenerationAssembly
import com.glosso.server.generation.GenerationConfig
import com.glosso.server.generation.GenerationJob
import com.glosso.server.generation.GenerationRuntime
import com.glosso.server.generation.GenerationStore
import com.glosso.server.generation.JdbcGenerationStore
import com.glosso.server.generation.JobStatus
import com.glosso.server.generation.LearnerProfileService
import com.glosso.server.generation.NeedClass
import com.glosso.server.generation.NeedConfig
import com.glosso.server.generation.NeedService
import com.glosso.server.generation.PhonemeSnapshot
import com.glosso.server.generation.PoolConfig
import com.glosso.server.generation.ProfileSyncInput
import com.glosso.server.generation.PublishedContentCatalog
import com.glosso.server.generation.SchedulerConfig
import com.glosso.server.generation.UserNeed

const val SERVER_VERSION = "0.11.0"

private val logger = KotlinLogging.logger("GlossoServer")

/**
 * Failed credential checks by gate. Kept process-wide so the per-request guards
 * (which do not see the Prometheus registry) can report without threading it
 * through every call site. See A09 in docs/OWASP-BACKEND.md: a spike here is the
 * signal that someone is guessing admin, ingest, client or RTDN credentials.
 */
private val authFailureCounts = ConcurrentHashMap<String, AtomicInteger>()

internal fun recordAuthFailure(gate: String) {
    authFailureCounts.computeIfAbsent(gate) { AtomicInteger() }.incrementAndGet()
}

// Request limits. Analytics is optional and must never be a way to stuff the
// database or burn CPU, and scoring must not accept unbounded audio.
private const val MAX_TEXT = 500
private const val MAX_IPA = 2_000
private const val MAX_ACCENT_REFERENCES = 32
private const val MAX_AUDIO_BASE64 = 7_000_000
private const val MAX_EVENTS = 50
private const val MAX_EVENT_NAME = 80
private const val MAX_PROPERTIES = 2_000
private const val MAX_NOTE = 400
private const val MAX_ID = 64
// A queued event keeps its device time only within this window; outside it the
// clock is not trusted and the event is stored at the time it arrives.
private val MAX_EVENT_AGE = java.time.Duration.ofDays(7)
private val MAX_EVENT_SKEW = java.time.Duration.ofMinutes(10)
private const val MAX_ID_TOKEN = 4_096
private const val MAX_PHONEMES = 64
private const val MAX_PHONEME = 16
private const val MAX_PATTERN = 64
private const val MAX_SYNC_BODY = 64_000

/**
 * Hard cap on a whole request body, checked from `Content-Length` before the
 * body is read. Slightly above [MAX_AUDIO_BASE64] (7 MB of base64) to leave room
 * for the JSON envelope. Caddy enforces the same cap at the edge, including a
 * chunked body with no `Content-Length`; this is the in-app backstop.
 */
private const val MAX_REQUEST_BODY = 9_000_000

/**
 * Parses `GLOSSO_CORS_ORIGINS` into (host, scheme) pairs. Accepts full origins
 * (`https://glossostudio.com`) or bare hosts (`glossostudio.com`, assumed
 * https). A wildcard entry is dropped: the allowlist is always explicit.
 */
internal fun parseCorsOrigins(raw: String?): List<Pair<String, String>> =
    raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() && it != "*" }?.map { origin ->
        val scheme = origin.substringBefore("://", missingDelimiterValue = "")
        val host = if (scheme.isEmpty()) origin else origin.substringAfter("://")
        host to scheme.ifEmpty { "https" }
    }.orEmpty()

/** Shared client credential header for `/v1/score`; see `GLOSSO_API_KEY`. */
private const val API_KEY_HEADER = "X-Api-Key"

/**
 * Client IP written by the trusted Caddy edge after it resolves the real client.
 * The app trusts only this, never `X-Forwarded-For`: behind Cloudflare the
 * leftmost XFF value is whatever the caller sent.
 */
private const val EDGE_CLIENT_IP_HEADER = "X-Real-Client-IP"

/** Service credential header for the profile ingest; see `GLOSSO_INGEST_TOKEN`. */
private const val INGEST_TOKEN_HEADER = "X-Ingest-Token"

/** Lenient on unknown keys, mirroring the server's JSON content negotiation. */
private val requestJson = Json { ignoreUnknownKeys = true }

/**
 * Field names that must never appear in a profile sync: student audio and
 * personal data. The values the engine needs are aggregate stats, so a request
 * carrying any of these is rejected before it is read as a profile. Keys are
 * matched case- and separator-insensitively (`audioBase64`, `audio_base64`).
 */
private val FORBIDDEN_SYNC_KEYS: Set<String> = setOf(
    // Audio of the learner: stays on the device, never reaches the server.
    "audio", "audiobase64", "audioinline", "recording", "recordingbase64",
    "pcm", "samples", "waveform", "voiceprint", "wav", "mp3", "ogg", "m4a", "rawaudio",
    // Personal data.
    "email", "phone", "phonenumber", "mobilenumber", "fullname", "firstname", "lastname",
    "name", "username", "address", "street", "postalcode", "zip", "ssn", "dni", "nif",
    "cpf", "passport", "birthdate", "birthday", "birthyear", "gender", "location",
    "latitude", "longitude", "gps", "contacts", "iban", "cardnumber",
)

/** Closed value sets for the profile sync, so a stored field cannot smuggle PII. */
private val SYNC_POSITIONS = setOf("initial", "medial", "final")
private val SYNC_TRENDS = setOf("improving", "stable", "declining", "unknown")

/** A curated error-rule id, e.g. `en.th.stopping.t`. Not free text. */
private val RULE_ID = Regex("^[a-z]{2}\\.[a-z0-9._-]{1,60}$")

private val CONTENT_KINDS =
    setOf("DRILL", "WORD", "STORY", "DIALOGUE", "ARTICLE", "EXERCISE", "LESSON")

/** Raised before a body is read when its declared size exceeds the cap. */
private class PayloadTooLargeException : RuntimeException("request body exceeds $MAX_REQUEST_BODY bytes")

/**
 * Rejects an oversized body from its `Content-Length` before content negotiation
 * reads and deserializes it, so a big POST is never materialized in memory
 * (SEC-03). Throwing aborts routing and StatusPages answers 413.
 */
private val RequestBodyLimit = createApplicationPlugin("RequestBodyLimit") {
    onCall { call ->
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MAX_REQUEST_BODY) {
            throw PayloadTooLargeException()
        }
    }
}

/**
 * HSTS only when the client-facing hop was https (see [isSecureRequest]). A
 * too-aggressive policy is hard to undo, so 180 days with includeSubDomains.
 * Caddy sets its own copy at the edge; this is the in-app backstop.
 */
private val HstsHeader = createApplicationPlugin("HstsHeader") {
    onCall { call ->
        if (call.isSecureRequest()) {
            call.response.headers.append(
                HttpHeaders.StrictTransportSecurity,
                "max-age=15552000; includeSubDomains",
            )
        }
    }
}

fun main() {
    if (System.getenv("GLOSSO_DEBUG") != null) EngineLog.enabled = true
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // The slow-client window (slowloris, hung uploads) and the response timeout
    // are enforced by the Caddy edge (`servers { timeouts { ... } }` in
    // deploy/Caddyfile); inference keeps its own queue timeout
    // (GLOSSO_QUEUE_WAIT_MS). The app only ever sees Caddy, never the Internet.
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = { module() })
        .start(wait = true)
}

private fun defaultAssetsDir(): File =
    File(System.getenv("GLOSSO_ASSETS_DIR") ?: "data/assets")

/**
 * Holds the heavy assets as they become available. Loading runs in the background
 * so startup (and the health check) never blocks on a ~600 MB model.
 *
 * Everything but the model is per language: the model is shared (it was trained on
 * ~60 espeak inventories), while G2P, drills and the manifest live under
 * `content/<language>` (or the assets root for a legacy English-only deployment).
 */
private class AssetState {
    val g2p = ConcurrentHashMap<String, G2P>()
    val drills = ConcurrentHashMap<String, DrillBank>()
    val manifests = ConcurrentHashMap<String, ContentManifest>()
    /** Published accent references per language, keyed by sentence; see [ContentDb.accentReferences]. */
    val accentReferences = ConcurrentHashMap<String, Map<String, Map<String, String>>>()
    @Volatile var recognizer: PhonemeRecognizer? = null
    /** Recognizers of the languages that do not use the default model; see [languageModels]. */
    val languageRecognizers = ConcurrentHashMap<String, PhonemeRecognizer>()
    @Volatile var status: String = "loading"
    @Volatile var error: String? = null
}

/** The recognizer that scores [language]: its own model when one is configured, else the default. */
private fun AssetState.recognizerFor(language: String): PhonemeRecognizer? =
    languageRecognizers[language] ?: recognizer

/**
 * Parses `GLOSSO_MODEL_FILES` ("ru=model_a.onnx,de=model_a.onnx"): the model file,
 * inside the assets directory, that scores each listed language instead of the
 * default one. The default model is fine-tuned on English and no longer writes
 * other languages in their own phone convention, so those need the multilingual
 * base model. Unset means one model for everything, as before.
 */
internal fun languageModels(raw: String?): Map<String, String> =
    raw.orEmpty().split(',').mapNotNull { entry ->
        val language = entry.substringBefore('=', "").trim().lowercase()
        val file = entry.substringAfter('=', "").trim()
        if (language.isEmpty() || file.isEmpty() || '/' in file || '\\' in file) null else language to file
    }.toMap()

/**
 * Where a language's content lives. Prefers `content/<code>`; a legacy deployment
 * that still ships English flat in the assets root keeps working.
 */
internal fun contentDir(assetsDir: File, pack: LanguagePack): File {
    val scoped = File(assetsDir, "content/${pack.code}")
    if (scoped.exists()) return scoped
    return if (pack.code == LanguagePacks.default.code) assetsDir else scoped
}

/**
 * Builds the G2P for a language from its lexicon asset. English uses CMUdict;
 * other languages use the per-language lexicon the publisher writes. Returns null
 * when the asset is missing, so a deployment without it simply cannot phonemize
 * server-side (the client sends `expectedIpa`).
 */
private fun loadG2p(assetsDir: File, pack: LanguagePack): G2P? {
    val assetName = pack.g2pAsset ?: return null
    val candidates = listOf(
        File(contentDir(assetsDir, pack), assetName),
        File(assetsDir, assetName),
    )
    val file = candidates.firstOrNull { it.isFile } ?: return null
    return when (pack.g2pKind) {
        G2PKind.CMU_DICT -> CmuDictG2P.fromLines(file.readLines())
        G2PKind.LEXICON -> LexiconG2P.fromLines(file.readLines())
        G2PKind.PRECOMPUTED -> null
    }
}

/**
 * Loads the reviewed content for one language. Prefers the shipped `content.db`
 * (text, reference IPA, audio paths); falls back to the JSON banks under
 * `drills/`. `manifest.json` catalogs what the app downloads. Every drill is
 * re-validated against the language's G2P on load, so hand-edited or corrupted
 * content is dropped instead of being shown to a learner.
 */

/**
 * Content fingerprint that clients put in every asset URL (`?v=`), so a rebuilt
 * catalogue is a new URL and no cache - Cloudflare's four hours included - can
 * hide it. It must change whenever any served asset changes: the previous
 * version only tracked the directory mtime, and overwriting an audio file in
 * place never touches it, so updates looked invisible.
 *
 * `content.db` is hashed by bytes (small, and it is rewritten on every publish
 * even when unchanged). `audio/` and `images/` are listed per file with size and
 * mtime: `sync-content.sh` uses `rsync -a`, which preserves mtimes, so the
 * fingerprint is stable across restarts and moves exactly when a file changes.
 */
private fun assetVersionFor(dir: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun feed(part: String) {
        digest.update(part.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }

    var tracked = false
    val db = File(dir, "content.db")
    if (db.isFile) {
        feed("content.db")
        db.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.update(0)
        tracked = true
    }
    for (sub in listOf("audio", "images")) {
        val base = File(dir, sub)
        if (!base.isDirectory) continue
        base.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).path }
            .sorted()
            .forEach { relative ->
                val file = File(dir, relative)
                feed("$relative:${file.length()}:${file.lastModified()}")
                tracked = true
            }
    }
    if (!tracked) return ""
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}

/** Bump when the manifest's served shape changes, to bust warm client caches. */
private const val MANIFEST_ETAG_REVISION = "2"

/**
 * Sizes of every downloadable asset, keyed by asset-relative path. The server
 * has the files, so it can publish them in the manifest: a client then adds up
 * a lesson's size locally instead of one network round trip per file.
 */
internal fun assetSizeIndex(dir: File): Map<String, Long> {
    val sizes = LinkedHashMap<String, Long>()
    for (sub in listOf("audio", "images")) {
        val base = File(dir, sub)
        if (!base.isDirectory) continue
        base.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .forEach { file -> sizes[file.relativeTo(dir).path] = file.length() }
    }
    return sizes
}

/**
 * The sizes the manifest serves: the files on disk when the server has them
 * (they include the TTS audio only the server holds, so they can never go
 * stale), otherwise the sizes the publisher stamped into `manifest.json`, so a
 * content-only deploy still gets a size instead of one round trip per file.
 */
internal fun servedAssetSizes(disk: Map<String, Long>, published: Map<String, Long>): Map<String, Long> =
    if (disk.isNotEmpty()) disk else published

private fun loadContent(assetsDir: File, pack: LanguagePack, state: AssetState) {
    val dir = contentDir(assetsDir, pack)
    val dbFile = File(dir, "content.db")
    val fromDb = ContentDb.drills(dbFile)
    val merged = if (fromDb != null) {
        DrillBank(drills = fromDb)
    } else {
        val drillDir = File(dir, "drills")
        val banks = if (drillDir.isDirectory) {
            drillDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    runCatching { DrillBankLoader.parse(file.readText()) }
                        .onFailure { logger.warn(it) { "Ignoring unreadable drill bank ${file.name}" } }
                        .getOrNull()
                }.orEmpty()
        } else {
            emptyList()
        }
        if (banks.isEmpty()) null else DrillBankLoader.merge(banks)
    }

    val accentReferences = ContentDb.accentReferences(dbFile)
    if (accentReferences.isNotEmpty()) {
        state.accentReferences[pack.code] = accentReferences
        logger.info { "Accent references [${pack.code}]: ${accentReferences.size} sentences" }
    }

    val g2p = state.g2p[pack.code]
    if (merged != null) {
        state.drills[pack.code] = if (g2p == null) {
            merged
        } else {
            val good = merged.drills.filter { drill ->
                val result = DrillValidator.validate(drill, g2p)
                if (!result.ok) {
                    logger.warn { "Dropping invalid drill ${drill.id}: ${result.problems.joinToString { it.code }}" }
                }
                result.ok
            }
            if (good.size != merged.drills.size) {
                logger.info { "Drill bank [${pack.code}]: ${good.size}/${merged.drills.size} items passed validation" }
            }
            merged.copy(drills = good)
        }
    }

    val manifestFile = File(dir, "manifest.json")
    val stored = if (manifestFile.isFile) {
        runCatching { DrillBankLoader.json.decodeFromString<ContentManifest>(manifestFile.readText()) }
            .onFailure { logger.warn(it) { "Ignoring unreadable content manifest for ${pack.code}" } }
            .getOrNull()
    } else {
        null
    }
    // The catalog is the source of truth for levels and themes: derive them from
    // the shipped DB and fold them into the manifest, so publishing a new level
    // or theme in content.db reaches the app without hand-editing manifest.json.
    val dbLevels = ContentDb.levels(dbFile)
    val dbThemes = ContentDb.themes(dbFile)
    val loadedManifest = stored?.mergeCatalog(dbLevels, dbThemes)
        ?: if (dbLevels.isNotEmpty() || dbThemes.isNotEmpty()) {
            ContentManifest(levels = dbLevels, themes = dbThemes.map { ThemeModel(it) })
        } else {
            null
        }
    if (loadedManifest != null) {
        state.manifests[pack.code] = loadedManifest.copy(
            assetVersion = assetVersionFor(dir),
            assets = servedAssetSizes(assetSizeIndex(dir), loadedManifest.assets),
        )
    }

    logger.info {
        "Content [${pack.code}] loaded: ${state.drills[pack.code]?.drills?.size ?: 0} drills " +
            "(${if (fromDb != null) "content.db" else "json"}), " +
            "levels=${state.manifests[pack.code]?.levels ?: emptyList()}, " +
            "manifest=${state.manifests[pack.code] != null}"
    }
}

/**
 * Folds the levels and themes actually present in the catalog into [this]
 * manifest. Existing theme labels win; themes the manifest did not know about
 * are appended with the id as their only label.
 */
private fun ContentManifest.mergeCatalog(levels: List<String>, themes: List<String>): ContentManifest {
    val known = this.themes.map { it.id }.toSet()
    val extra = themes.filter { it !in known }.map { ThemeModel(it) }
    return copy(
        levels = (this.levels + levels).distinct().sorted(),
        themes = this.themes + extra,
    )
}

fun Application.module() = module(defaultAssetsDir())

/**
 * Production entry point: reads the admin token, the client API key and the
 * analytics database from the environment. Delegates to the injectable overload
 * so tests can run without a database and still exercise the token-protected
 * endpoints.
 */
fun Application.module(assetsDir: File) {
    val adminToken = System.getenv("GLOSSO_ADMIN_TOKEN")?.takeIf { it.isNotBlank() }
    val apiKey = System.getenv("GLOSSO_API_KEY")?.takeIf { it.isNotBlank() }
    val ingestToken = System.getenv("GLOSSO_INGEST_TOKEN")?.takeIf { it.isNotBlank() }
    val analytics = AnalyticsStore.fromEnv { logger.info { it } }
    val mirror = analytics?.let { UmamiMirror.fromEnv { message -> logger.info { message } } }
    // The generation control plane shares the analytics PostgreSQL. It is
    // optional: without a database the signal plane is unaffected and profile
    // sync answers 503.
    val database = ControlPlaneDatabase.open { logger.info { it } }
    val onlineStore = database?.let { JdbcGenerationStore(it.dataSource) }
    val published = PublishedContentCatalog.fromAssets(assetsDir, LanguagePacks.all.map { it.code })
    // Once the generated pool exists, the measured gap counts it too, so the
    // scheduler stops refilling a Need Class that is already covered.
    val catalog = if (onlineStore != null) {
        CompositeContentCatalog.of(published, CompositeContentCatalog.PoolCatalog(onlineStore))
    } else {
        published
    }
    module(
        assetsDir,
        adminToken,
        analytics,
        mirror = mirror,
        apiKey = apiKey,
        ingestToken = ingestToken,
        controlStore = database?.controlStore,
        contentCatalog = catalog,
        controlDatabase = database,
        entitlementStore = database?.let { JdbcEntitlementStore(it.dataSource) },
        accountSecret = AccountSecret.fromEnv(),
        googleVerifier = GoogleSignInConfig.verifierFromEnv(),
    )
}

/**
 * @param assetsDir directory holding the model, vocabulary, CMUdict and (later)
 *   level databases. Injected so tests can point at a temp directory.
 * @param adminToken guards /metrics and the analytics admin views.
 * @param analytics first-party event store; null disables analytics cleanly.
 * @param billing Play purchase verification and RTDN; defaults to the
 *   environment-configured service so tests can inject a fake Play API.
 * @param apiKey shared client credential for the scoring oracle. When set,
 *   `/v1/score` requires the `X-Api-Key` header; null leaves it open for
 *   development builds and emulators (production sets `GLOSSO_API_KEY`).
 * @param ingestToken service credential for `/v1/profiles/sync`, sent in the
 *   `X-Ingest-Token` header. It fails closed: null or blank means the ingest is
 *   rejected with 401, so an exposed deployment cannot enqueue cost-bearing
 *   generation until the operator sets `GLOSSO_INGEST_TOKEN`.
 * @param controlStore generation control plane (profiles, needs, demand). Null
 *   disables `/v1/profiles/sync` and the demand routes cleanly, so tests and a
 *   deployment without PostgreSQL still run the signal plane.
 * @param contentCatalog published content used to measure the gap. Defaults to
 *   none, so aggregation reports a full gap until the catalog is wired.
 * @param controlDatabase shared PostgreSQL of the control plane, when present.
 *   Gives the generation queue one pool and one migration run instead of one per
 *   component. Null disables generation cleanly.
 * @param generation assembled phase-2 runtime. Injected in tests; production
 *   builds it when Redis and a TTS provider are configured.
 * @param entitlementStore identity and entitlement persistence. Null disables
 *   `GET /v1/me` cleanly; `/v1/config` is served from [productConfig] regardless.
 * @param productConfig the product configuration served by `GET /v1/config`.
 *   Defaults to the environment; injected so tests can pin a phase and a date.
 * @param accountSecret server secret for sign-in nonces; production reads
 *   `GLOSSO_ACCOUNT_SECRET`. Null (the default, so a test never picks up the
 *   developer's environment) disables Sign in with Google with a 503.
 * @param googleVerifier Google ID token verification; production reads
 *   `GLOSSO_GOOGLE_CLIENT_ID`. Null disables Sign in with Google with a 503.
 */
fun Application.module(
    assetsDir: File,
    adminToken: String?,
    analytics: AnalyticsStore?,
    /** A dashboard copy of the stored events (Umami); null keeps Postgres only. */
    mirror: AnalyticsMirror? = null,
    billing: BillingService = BillingService.fromEnv(),
    apiKey: String? = null,
    ingestToken: String? = null,
    recognizer: PhonemeRecognizer? = null,
    controlStore: ControlPlaneStore? = null,
    contentCatalog: ContentCatalog = EmptyContentCatalog,
    controlDatabase: ControlPlaneDatabase? = null,
    generation: GenerationRuntime? = null,
    entitlementStore: EntitlementStore? = null,
    productConfig: ProductConfig = ProductConfig.fromEnv(),
    multiAccent: MultiAccentConfig = MultiAccentConfig.fromEnv(),
    voiceIngest: VoiceIngestService? = null,
    accountSecret: AccountSecret? = null,
    googleVerifier: GoogleIdTokenVerifier? = null,
) {
    val state = AssetState()
    val voices = voiceIngest ?: VoiceIngestService(
        VoiceRegistry.fromEnv(assetsDir),
        VoiceAudioFetchers.fromEnv { message -> logger.warn { message } },
    )
    logger.info { "Multi-accent scoring: ${if (multiAccent.enabled) "on" else "off"}; voice fetcher=${voices.fetcher.name}" }
    val bootstrap = System.getenv("GLOSSO_BOOTSTRAP") == "1"
    val entitlementService = entitlementStore?.let {
        EntitlementService(
            it,
            productConfig,
            accountSecret = accountSecret,
            googleVerifier = googleVerifier,
        )
    }
    logger.info {
        "Google sign-in: ${if (accountSecret != null && googleVerifier != null) "on" else "off"}"
    }

    // Inference is CPU-bound and parallel runs thrash the CPU (measured: two
    // concurrent takes are ~30x slower than two serial ones on a 2-vCPU host), so
    // the default is one at a time. Raise GLOSSO_MAX_CONCURRENT only after a load
    // test shows the host benefits from it.
    val maxConcurrent = System.getenv("GLOSSO_MAX_CONCURRENT")?.toIntOrNull()?.coerceAtLeast(1)
        ?: 1
    val queueWaitMs = System.getenv("GLOSSO_QUEUE_WAIT_MS")?.toLongOrNull() ?: 30_000L
    val inferenceSlots = Semaphore(maxConcurrent)
    val inFlight = AtomicInteger(0)
    logger.info { "Inference gate: maxConcurrent=$maxConcurrent, queueWaitMs=$queueWaitMs" }

    // First-party analytics: the store the caller passed (the production
    // overload builds it from the environment); null disables it cleanly.
    val analyticsStatus = if (analytics != null) "ready" else "disabled"
    val analyticsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Generation control plane: profile ingest, need derivation and periodic
    // demand aggregation. It shares the analytics PostgreSQL and never touches
    // the request path of scoring or content. `generationScope` is only for the
    // background aggregator, so a slow cycle can never block a user request.
    val generationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val profileService = controlStore?.let { LearnerProfileService(it) }
    val needService = controlStore?.let { NeedService(it, NeedConfig.DEFAULT) }
    val poolConfig = PoolConfig.of(
        System.getenv("GLOSSO_POOL_MIN")?.toIntOrNull() ?: 20,
        System.getenv("GLOSSO_POOL_TARGET")?.toIntOrNull() ?: 50,
        System.getenv("GLOSSO_POOL_MAX")?.toIntOrNull() ?: 100,
    )
    val demandService = controlStore?.let { DemandService(it, contentCatalog, poolConfig) }
    val demandScheduler = demandService?.let { service ->
        DemandScheduler(service, SchedulerConfig.fromEnv(), generationScope) { message -> logger.info { message } }
    }
    logger.info { "Demand pool: min=${poolConfig.minimum}, target=${poolConfig.target}, max=${poolConfig.maximum}" }

    // Phase 2: the content pool and the generation queue. Built only when a
    // shared database is available and Redis and a TTS provider are configured;
    // otherwise the phase-1 demand scheduler keeps the snapshot fresh.
    val generationStore = generation?.store ?: controlDatabase?.let { JdbcGenerationStore(it.dataSource) }
    val generationRuntime = generation ?: run {
        if (controlDatabase != null && generationStore != null && demandService != null) {
            val (provider, model) = GenerationAssembly.providerIdentity()
            GenerationAssembly.fromEnv(
                database = controlDatabase,
                generationStore = generationStore,
                demandService = demandService,
                config = GenerationConfig.fromEnv(provider = provider, model = model),
                scope = generationScope,
                schedulerConfig = SchedulerConfig.fromEnv(),
                log = { message -> logger.info { message } },
            )
        } else {
            null
        }
    }
    if (generationRuntime != null) {
        generationRuntime.start()
        logger.info { "Generation runtime started" }
    } else {
        demandScheduler?.start()
    }

    monitor.subscribe(ApplicationStopped) {
        demandScheduler?.stop()
        generationRuntime?.close()
        generationScope.cancel()
        analyticsScope.cancel()
        analytics?.close()
        mirror?.close()
        controlDatabase?.close() ?: controlStore?.close()
    }

    fun loadAllG2p() {
        for (pack in LanguagePacks.all) {
            loadG2p(assetsDir, pack)?.let { state.g2p[pack.code] = it }
        }
    }

    // G2P is small; load it synchronously so reference IPA is available immediately.
    loadAllG2p()

    // Content is small JSON; load it synchronously too and validate it against the
    // language's dictionary, so an invalid bank can never reach a learner.
    LanguagePacks.all.forEach { loadContent(assetsDir, it, state) }

    // Tests may inject a recognizer; production loads the ONNX model below.
    if (recognizer != null) state.recognizer = recognizer

    Thread({
        try {
            if (bootstrap) AssetBootstrapper.ensure(assetsDir)
            loadAllG2p()
            LanguagePacks.all.forEach { loadContent(assetsDir, it, state) }
            if (analytics != null) {
                // Daily, not only at start: the server can run for weeks, and the
                // policy promises the window. The dashboard copy keeps the same one.
                val retentionDays = System.getenv("GLOSSO_ANALYTICS_RETENTION_DAYS")?.toIntOrNull() ?: 180
                analyticsScope.launch {
                    while (true) {
                        runCatching { analytics.purgeOlderThan(retentionDays) }
                            .onFailure { logger.warn(it) { "analytics purge failed" } }
                        runCatching { mirror?.purgeOlderThan(retentionDays) }
                            .onFailure { logger.warn { "umami purge failed: ${it.message}" } }
                        delay(24.hours)
                    }
                }
            }

            if (recognizer != null) {
                state.recognizer = recognizer
            } else {
                // GLOSSO_MODEL_FILE lets production pick a different artifact (e.g.
                // the selective int8 model) without renaming the file the app
                // downloads or touching code. Defaults to the shipped fp16.
                val modelName = System.getenv("GLOSSO_MODEL_FILE")?.takeIf { it.isNotBlank() }
                    ?: PhonemeModelConfig.MODEL_FILE
                val modelFile = File(assetsDir, modelName)
                val vocabFile = File(assetsDir, PhonemeModelConfig.VOCAB_FILE)
                if (modelFile.isFile && vocabFile.isFile) {
                    val loaded = Wav2Vec2PhonemeRecognizer(modelFile, vocabFile)
                    loaded.initialize()
                    state.recognizer = loaded
                }
                // One recognizer per distinct file, shared by the languages that name it.
                val byFile = HashMap<String, PhonemeRecognizer>()
                for ((language, fileName) in languageModels(System.getenv("GLOSSO_MODEL_FILES"))) {
                    val file = File(assetsDir, fileName)
                    if (!file.isFile || !vocabFile.isFile) {
                        logger.warn { "model for '$language' not found: ${file.path}; it keeps the default model" }
                        continue
                    }
                    val loaded = byFile.getOrPut(fileName) {
                        Wav2Vec2PhonemeRecognizer(file, vocabFile).also { it.initialize() }
                    }
                    if (loaded.state != ModelState.READY) {
                        logger.warn { "model $fileName for '$language' did not load (${loaded.error}); it keeps the default model" }
                        continue
                    }
                    state.languageRecognizers[language] = loaded
                    logger.info { "language '$language' is scored with $fileName" }
                }
            }
            state.status = "ready"
        } catch (e: Exception) {
            logger.error(e) { "Asset loading failed" }
            state.status = "error"
            state.error = e.message ?: "asset loading failed"
        }
    }, "asset-loader").apply { isDaemon = true }.start()

    val prometheus = io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
        io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT,
    )
    val scoreOk = prometheus.counter("glosso_score_requests_total", "outcome", "ok")
    val scoreBad = prometheus.counter("glosso_score_requests_total", "outcome", "rejected")
    val scoreBusy = prometheus.counter("glosso_score_requests_total", "outcome", "busy")
    val scoreError = prometheus.counter("glosso_score_requests_total", "outcome", "error")
    val eventsIngested = prometheus.counter("glosso_events_ingested_total")
    val serverErrors = prometheus.counter("glosso_server_errors_total")
    // A09: failed credential checks are alertable. One series per gate, fed from
    // the process-wide counter the request guards bump.
    for (gate in listOf("admin", "ingest", "api_key", "rtdn")) {
        io.micrometer.core.instrument.FunctionCounter
            .builder("glosso_auth_failures_total", authFailureCounts) { counts ->
                counts[gate]?.get()?.toDouble() ?: 0.0
            }
            .tag("gate", gate)
            .register(prometheus)
    }
    val inferenceTimer = io.micrometer.core.instrument.Timer.builder("glosso_inference_seconds")
        .publishPercentiles(0.5, 0.95)
        .register(prometheus)
    io.micrometer.core.instrument.Gauge
        .builder("glosso_inference_in_flight") { inFlight.get().toDouble() }
        .register(prometheus)

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
    install(RequestBodyLimit)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        // The microphone is never needed by the API itself; the public demo
        // grants it per-origin on the website, not here.
        header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
        // The API serves JSON and one token-protected HTML dashboard with inline
        // styles and no scripts, so nothing else needs to load.
        header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
    }
    install(HstsHeader)
    install(CallLogging) {
        filter { call ->
            val path = call.request.path()
            path != "/health" && path != "/ready" && path != "/metrics"
        }
        // Never log the query string, the Authorization header or any api key:
        // only the method, the decoded path (control characters stripped) and the
        // status. A `?token=` or `?api_key=` therefore cannot reach the log even
        // if a client sends one (SEC-08 / A09). The formatter is pinned by
        // SecurityHardeningTest so a future change cannot silently widen it.
        format { call -> call.accessLogLine() }
    }
    // CORS is only needed for a browser client; a native app sends no Origin. The
    // allowlist is explicit and never `*`; when nothing is configured nothing is
    // exposed. Entries may be full origins (`https://glossostudio.com`) or bare
    // hosts (`glossostudio.com`, assumed https).
    val corsOrigins = parseCorsOrigins(System.getenv("GLOSSO_CORS_ORIGINS"))
    if (corsOrigins.isNotEmpty()) {
        install(CORS) {
            corsOrigins.forEach { (host, scheme) -> allowHost(host, schemes = listOf(scheme)) }
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(API_KEY_HEADER)
            allowHeader(INGEST_TOKEN_HEADER)
            allowHeader("X-Device-Id")
        }
    }
    install(RateLimit) {
        // All budgets are keyed by the edge-resolved client IP, never by a
        // client-supplied forwarding header (SEC-05). See [clientLimitKey].
        register(RateLimitName("score")) {
            rateLimiter(limit = 60, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
        register(RateLimitName("events")) {
            rateLimiter(limit = 120, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
        // Profile sync writes a handful of rows; the same budget as events keeps
        // a misbehaving client from hammering PostgreSQL.
        register(RateLimitName("profiles")) {
            rateLimiter(limit = 120, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
        // Verification hits the Play API, so a client cannot turn the endpoint
        // into an amplifier against Google.
        register(RateLimitName("billing")) {
            rateLimiter(limit = 30, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
        // Config and identity are cheap but write a row per first sight; the same
        // modest per-IP budget keeps a misbehaving client from hammering them.
        register(RateLimitName("product")) {
            rateLimiter(limit = 120, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
        // Sign in with Google: it can move an account between devices, so the
        // budget is deliberately tighter than the product reads.
        register(RateLimitName("account")) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
        // Content is read-heavy and cacheable; a modest per-IP budget stops a
        // single client scraping the catalogue page by page (SEC-13).
        register(RateLimitName("content")) {
            rateLimiter(limit = 240, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
        // /assets is the bandwidth-heavy surface (the ~600 MB model, audio and
        // art). A per-IP budget stops one client draining the box or scraping the
        // whole catalogue; a normal sync stays well under it (A04/SEC-13).
        register(RateLimitName("assets")) {
            rateLimiter(limit = 600, refillPeriod = 60.seconds)
            requestKey { call -> call.clientLimitKey() }
        }
    }
    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Request body is too large"))
        }
        // Two account flows raced on the same user (a move, a merge, a first
        // sign-in). Nothing was written; the client retries on the new state.
        exception<AccountChangedConcurrently> { call, _ ->
            logger.info { "Account changed concurrently on ${sanitizeLogText(call.request.path())}" }
            call.respond(HttpStatusCode.Conflict, ErrorResponse("The account changed meanwhile; try again"))
        }
        exception<Throwable> { call, cause ->
            serverErrors.increment()
            logger.error(cause) {
                "Unhandled server error on ${sanitizeLogText(call.request.httpMethod.value)} " +
                    sanitizeLogText(call.request.path())
            }
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal error"),
            )
        }
    }

    install(MicrometerMetrics) {
        registry = prometheus
        meterBinders = listOf(
            io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics(),
            io.micrometer.core.instrument.binder.jvm.JvmGcMetrics(),
            io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics(),
            io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics(),
            io.micrometer.core.instrument.binder.system.ProcessorMetrics(),
            io.micrometer.core.instrument.binder.system.UptimeMetrics(),
        )
    }

    routing {
        // Public liveness only. The build version is not exposed: it is free
        // reconnaissance and there is an admin view (`/stats`) when needed.
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    recognition = state.recognizer?.state == ModelState.READY,
                    assets = state.status,
                    analytics = analyticsStatus,
                ),
            )
        }

        // Readiness for the proxy/uptime monitor: only ready once the model loaded.
        get("/ready") {
            val ready = state.status == "ready" && state.recognizer?.state == ModelState.READY
            if (ready) {
                call.respond(HttpStatusCode.OK, mapOf("ready" to true))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("ready" to false, "assets" to state.status),
                )
            }
        }

        // Prometheus scrape, token protected because it exposes internals.
        get("/metrics") {
            if (!call.adminAuthorized(adminToken)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            }
            call.respondText(prometheus.scrape(), ContentType.Text.Plain.withCharset(Charsets.UTF_8))
        }

        route("/v1") {
            rateLimit(RateLimitName("product")) {
                accentRoutes(multiAccent, voices) { adminAuthorized(adminToken) }
            }

            // Product configuration: the authority for phase, beta dates, plans,
            // flags and the minimum client version. Served like the content
            // manifest, with `no-cache` + ETag, so a warm client gets a 304 and
            // only pays for the body when the config actually changes. Built per
            // request because the phase follows the calendar: past the beta end
            // date the body (and so the ETag) changes without a restart.
            // Contract: docs/PRODUCT-CONFIG-CONTRACT.md.
            rateLimit(RateLimitName("product")) {
                get("/config") {
                    val productConfigJson = productConfig.toJson(Instant.now())
                    val productConfigEtag = "\"${sha256Hex(productConfigJson).take(16)}\""
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.response.header(HttpHeaders.ETag, productConfigEtag)
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    if (ifNoneMatch != null &&
                        ifNoneMatch.split(",").any { it.trim() == productConfigEtag || it.trim() == "*" }
                    ) {
                        call.respond(HttpStatusCode.NotModified)
                    } else {
                        call.respondText(productConfigJson, ContentType.Application.Json)
                    }
                }

                // The server-computed identity snapshot for one device. The plan
                // is decided here, never written by the client, and Beta Founder is
                // an independent, permanent status. Contract: section 4 of
                // docs/PRODUCT-CONFIG-CONTRACT.md.
                get("/me") {
                    val service = entitlementService ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Identity is not configured"),
                    )
                    val deviceId = call.request.headers["X-Device-Id"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("X-Device-Id is required"),
                        )
                    val me = withContext(Dispatchers.IO) { service.me(deviceId) }
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respond(me.toResponse())
                }
            }

            // Carry the account of this device to a new one, without any
            // personal data: `auth/google` signs in with a Google account
            // chosen on the device and moves the account here, so the Beta
            // Founder status and the lifetime PRO follow the person.
            rateLimit(RateLimitName("account")) {
                post("/auth/google/nonce") {
                    val service = entitlementService?.takeIf { it.googleSignInEnabled }
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Google sign-in is not configured"),
                        )
                    val deviceId = call.deviceIdOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("X-Device-Id is required"),
                        )
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respond(GoogleSignInNonceResponse(service.googleSignInNonce(deviceId)))
                }

                post("/auth/google") {
                    val service = entitlementService?.takeIf { it.googleSignInEnabled }
                        ?: return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Google sign-in is not configured"),
                        )
                    val deviceId = call.deviceIdOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("X-Device-Id is required"),
                        )
                    val idToken = runCatching { call.receive<GoogleSignInRequest>() }.getOrNull()?.idToken
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_TOKEN }
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("An ID token is required"),
                        )
                    val result = withContext(Dispatchers.IO) { service.signInWithGoogle(deviceId, idToken) }
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    when (result) {
                        is GoogleSignInResult.SignedIn -> call.respond(result.me.toResponse())
                        GoogleSignInResult.InvalidToken -> call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Invalid Google sign-in"),
                        )
                        GoogleSignInResult.Conflict -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("This device is linked to another Google account"),
                        )
                    }
                }
            }

            rateLimit(RateLimitName("score")) {
                post("/score") {
                    // The scoring calibration is an oracle: with the shared client
                    // key configured, an anonymous caller gets nothing back. The
                    // check is inside the rate-limited route so guesses are counted
                    // against the IP budget.
                    if (!call.apiKeyAuthorized(apiKey)) {
                        scoreBad.increment()
                        return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized"),
                        )
                    }
                    val request = call.receive<ScoreRequest>()
                    if (request.text.isBlank() || request.text.length > MAX_TEXT) {
                        scoreBad.increment()
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("text must be 1..$MAX_TEXT characters"),
                        )
                    }
                    request.audioBase64?.let {
                        if (it.length > MAX_AUDIO_BASE64) {
                            scoreBad.increment()
                            return@post call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse("audioBase64 is too large"),
                            )
                        }
                    }
                    request.expectedIpa?.let {
                        if (it.length > MAX_IPA) {
                            scoreBad.increment()
                            return@post call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse("expectedIpa is too large"),
                            )
                        }
                    }
                    request.actualIpa?.let {
                        if (it.length > MAX_IPA) {
                            scoreBad.increment()
                            return@post call.respond(
                                HttpStatusCode.PayloadTooLarge,
                                ErrorResponse("actualIpa is too large"),
                            )
                        }
                    }
                    if (request.accentIpa.size > MAX_ACCENT_REFERENCES ||
                        request.accentIpa.any { (k, v) -> k.length > MAX_ID || v.length > MAX_IPA }
                    ) {
                        scoreBad.increment()
                        return@post call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            ErrorResponse("accentIpa is too large"),
                        )
                    }

                    val pack = LanguagePacks.forScoring(request.language)
                    // The catalogue ships a reference IPA (the primary CMUdict
                    // reading); even then the scorer must accept the dictionary's
                    // alternate readings, or a valid British form (the /t/ in
                    // "thirty" against the American flap /d/) is charged as wrong.
                    val providedIpa = request.expectedIpa?.takeIf { it.isNotBlank() }
                    val pronunciations = state.g2p[pack.code]?.pronunciations(request.text)
                    val expectedIpa = providedIpa
                        ?: pronunciations?.canonical?.takeIf { it.isNotBlank() }
                        ?: run {
                            scoreBad.increment()
                            return@post call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Reference pronunciation is not available for this language"),
                            )
                        }
                    // Single source of truth for the widening rule (also baked into
                    // the content for the offline path): the client reference stays
                    // canonical and each dictionary alternate is accepted.
                    val expectedVariants = pronunciations
                        ?.let { widenReferenceVariants(expectedIpa, it.wordVariants) }
                        .orEmpty()

                    val usedAudio = !request.audioBase64.isNullOrBlank()
                    var actualConfidence: List<Float> = emptyList()
                    var timings: List<PhoneTimingModel> = emptyList()
                    var audioPcm: FloatArray? = null
                    var audioSampleRate = 0
                    var quality: PronunciationQualityModel? = null
                    val actualIpa: String = if (usedAudio) {
                        val recognizer = state.recognizerFor(pack.code) ?: run {
                            logger.warn { "recognition unavailable: assets=${state.status} ${state.error}" }
                            scoreError.increment()
                            return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorResponse("Recognition is unavailable"),
                            )
                        }
                        when (recognizer.state) {
                            ModelState.FAILED -> {
                                logger.warn { "recognition model failed to load: ${recognizer.error}" }
                                scoreError.increment()
                                return@post call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Recognition is unavailable"),
                                )
                            }
                            ModelState.LOADING, ModelState.UNINITIALIZED -> {
                                scoreError.increment()
                                return@post call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Recognition model is still loading; retry shortly"),
                                )
                            }
                            ModelState.READY -> Unit
                        }

                        // Suspend while waiting for a slot; never block a thread, or the
                        // dispatcher starves and inference collapses.
                        val admitted = withTimeoutOrNull(queueWaitMs) {
                            inferenceSlots.acquire()
                            true
                        } ?: false
                        if (!admitted) {
                            scoreBusy.increment()
                            call.response.header(HttpHeaders.RetryAfter, "2")
                            return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorResponse("Server busy, retry shortly"),
                            )
                        }
                        inFlight.incrementAndGet()
                        try {
                            val startedAt = System.nanoTime()
                            val sample = io.micrometer.core.instrument.Timer.start(prometheus)
                            val analysis = withContext(Dispatchers.Default) {
                                recognizer.analyze(request.audioBase64, expectedIpa)
                            }
                            sample.stop(inferenceTimer)
                            logger.info { "recognize done in ${(System.nanoTime() - startedAt) / 1_000_000} ms" }
                            // No usable speech is a learner state, not a failure:
                            // answer with a not-evaluated result (200) so the app
                            // says "try again somewhere quiet" instead of showing
                            // a 0/100 for an attempt nobody could hear.
                            analysis ?: run {
                                logger.info { "No usable speech; returning not-evaluated result" }
                                return@post call.respond(PronunciationFeedback(evaluated = false))
                            }
                            actualConfidence = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
                            timings = analysis.timings.orEmpty()
                            audioPcm = analysis.pcm
                            audioSampleRate = analysis.sampleRate
                            quality = analysis.quality
                            analysis.recognition.ipa
                        } finally {
                            inFlight.decrementAndGet()
                            inferenceSlots.release()
                        }
                    } else if (!request.actualIpa.isNullOrBlank()) {
                        request.actualIpa
                    } else {
                        scoreBad.increment()
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Provide audioBase64 or actualIpa"),
                        )
                    }

                    // Multi-accent (flagged): the take is also scored against the
                    // accent systems the language offers, each with its published
                    // whole-sentence reference, and the better score wins; off, it is
                    // the single-reference score unchanged. The references come
                    // from the request or, for the apps published before the
                    // content carried them, from the catalogue itself, looked up
                    // by the sentence.
                    val accentTarget = request.accent?.trim()?.takeIf { it.isNotEmpty() }?.let { AccentTarget(it) }
                    val accentReferences = request.accentIpa.ifEmpty {
                        state.accentReferences[pack.code]?.get(request.text.trim()).orEmpty()
                    }
                    val outcome = AccentScoring.score(
                        text = request.text,
                        canonicalIpa = expectedIpa,
                        actual = actualIpa,
                        language = pack.code,
                        actualConfidence = actualConfidence,
                        wordVariants = expectedVariants,
                        multiAccent = multiAccent.enabled,
                        accentReferences = accentReferences,
                        target = accentTarget,
                    )
                    val multi = outcome.accent
                    val result = outcome.result
                    // The feedback speaks the canonical reference's phones whichever
                    // reference decided the score; see [AccentScoring].
                    val scoredIpa = expectedIpa
                    val profile = PhoneticProfile.fold(request.profile, result.alignment)
                    val stress = StressAnalyzer.analyze(
                        timings = timings,
                        referenceWords = PhoneticComparator.referenceWords(scoredIpa),
                    )
                    // Cheap, model-free complement to the wav2vec2 score: a
                    // reference-free accent/fluency signal plus per-phone
                    // explanatory acoustics. Never touches the score above.
                    val cheap = audioPcm?.let { pcmFloat ->
                        CheapScorer.analyze(
                            DoubleArray(pcmFloat.size) { pcmFloat[it].toDouble() },
                            audioSampleRate,
                            timings,
                        )
                    }
                    val feedback = FeedbackAssembler.assemble(
                        text = request.text,
                        expectedIpa = scoredIpa,
                        result = result,
                        targetLanguage = pack.code,
                        uiLanguage = request.uiLanguage,
                        timings = timings,
                        profile = profile,
                        stress = stress,
                        fluency = cheap?.fluency,
                        acoustics = cheap?.acoustics.orEmpty(),
                        quality = quality,
                    ).copy(accentMatch = multi?.toModel(accentTarget))
                    scoreOk.increment()

                    // Server-side product analytics: no app change needed to see
                    // how scoring is actually used. Best-effort and off the path.
                    if (analytics != null && !call.analyticsOptedOut()) {
                        val deviceId = call.request.headers["X-Device-Id"]?.take(MAX_ID)
                        val properties = buildJsonObject {
                            put("language", JsonPrimitive(pack.code))
                            put("score", JsonPrimitive(result.score))
                            put("level", JsonPrimitive(result.level.name))
                            put("mastery", JsonPrimitive(result.isMastery))
                            put("audio", JsonPrimitive(usedAudio))
                            multi?.let { put("accent", JsonPrimitive(it.system.id)) }
                            put("phones", JsonPrimitive(result.alignment.size))
                            put(
                                "errors",
                                JsonPrimitive(feedback.errors.mapNotNull { it.ruleId }.joinToString(",")),
                            )
                        }.toString()
                        analyticsScope.launch {
                            val events = listOf(
                                AnalyticsEvent(
                                    name = "score_attempt",
                                    deviceId = deviceId,
                                    language = pack.code,
                                    propertiesJson = properties,
                                ),
                            )
                            runCatching { analytics.insert(events) }
                                .onFailure { logger.warn(it) { "analytics insert failed" } }
                                .onSuccess {
                                    runCatching { mirror?.send(events) }
                                        .onFailure { logger.warn { "umami mirror failed: ${it.message}" } }
                                }
                        }
                    }

                    call.respond(feedback)
                }

                // Audio in, model IPA out. The content pipeline uses this so the
                // reference pronunciation matches the model's own phone inventory
                // instead of a dictionary transcription.
                post("/phonemize") {
                    // Runs the model, so it takes the same client credential as
                    // scoring (no-op while GLOSSO_API_KEY is unset).
                    if (!call.apiKeyAuthorized(apiKey)) {
                        scoreBad.increment()
                        return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                    }
                    val request = call.receive<PhonemizeRequest>()
                    if (request.audioBase64.isBlank() || request.audioBase64.length > MAX_AUDIO_BASE64) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("audioBase64 is required and must be within limits"),
                        )
                    }
                    val recognizer = state.recognizer ?: run {
                        logger.warn { "recognition unavailable: assets=${state.status}" }
                        scoreError.increment()
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Recognition is unavailable"),
                        )
                    }
                    if (recognizer.state != ModelState.READY) {
                        scoreError.increment()
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Recognition model is not ready"),
                        )
                    }
                    val admitted = withTimeoutOrNull(queueWaitMs) {
                        inferenceSlots.acquire()
                        true
                    } ?: false
                    if (!admitted) {
                        scoreBusy.increment()
                        call.response.header(HttpHeaders.RetryAfter, "2")
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Server busy, retry shortly"),
                        )
                    }
                    inFlight.incrementAndGet()
                    val ipa = try {
                        withContext(Dispatchers.Default) { recognizer.recognize(request.audioBase64) }
                    } finally {
                        inFlight.decrementAndGet()
                        inferenceSlots.release()
                    }
                    if (ipa.isNullOrBlank()) {
                        scoreError.increment()
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse("No usable speech detected in the recording"),
                        )
                    }
                    scoreOk.increment()
                    call.respond(PhonemizeResponse(ipa))
                }

                // Force-align known text against TTS audio. The reference is the
                // intended CMUdict pronunciation placed on the audio; the greedy
                // decode comes back only as a diagnostic.
                post("/align") {
                    if (!call.apiKeyAuthorized(apiKey)) {
                        scoreBad.increment()
                        return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                    }
                    val request = call.receive<AlignRequest>()
                    if (request.text.isBlank() || request.text.length > MAX_TEXT) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("text must be 1..$MAX_TEXT characters"),
                        )
                    }
                    if (request.audioBase64.isBlank() || request.audioBase64.length > MAX_AUDIO_BASE64) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("audioBase64 is required and must be within limits"),
                        )
                    }
                    val alignPack = LanguagePacks.forScoring(request.language)
                    val alignPronunciations = state.g2p[alignPack.code]?.pronunciations(request.text)
                    val expectedIpa = alignPronunciations?.canonical?.takeIf { it.isNotBlank() }
                        ?: run {
                            scoreError.increment()
                            return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                ErrorResponse("Reference pronunciation is not available for this language"),
                            )
                        }
                    val recognizer = state.recognizerFor(alignPack.code) ?: run {
                        logger.warn { "recognition unavailable: assets=${state.status}" }
                        scoreError.increment()
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Recognition is unavailable"),
                        )
                    }
                    if (recognizer.state != ModelState.READY) {
                        scoreError.increment()
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Recognition model is not ready"),
                        )
                    }
                    val admitted = withTimeoutOrNull(queueWaitMs) {
                        inferenceSlots.acquire()
                        true
                    } ?: false
                    if (!admitted) {
                        scoreBusy.increment()
                        call.response.header(HttpHeaders.RetryAfter, "2")
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Server busy, retry shortly"),
                        )
                    }
                    inFlight.incrementAndGet()
                    val analysis = try {
                        withContext(Dispatchers.Default) { recognizer.analyze(request.audioBase64, expectedIpa) }
                    } finally {
                        inFlight.decrementAndGet()
                        inferenceSlots.release()
                    }
                    if (analysis == null) {
                        scoreError.increment()
                        return@post call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse("No usable speech detected in the recording"),
                        )
                    }
                    val confidences = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
                    val result = PhoneticComparator.calculateScoringResult(
                        text = request.text,
                        expected = expectedIpa,
                        actual = analysis.recognition.ipa,
                        language = alignPack.code,
                        actualConfidence = confidences,
                        expectedWordVariants = alignPronunciations?.wordVariants.orEmpty(),
                    )
                    scoreOk.increment()
                    call.respond(
                        AlignResponse(
                            expected = expectedIpa,
                            transcription = analysis.recognition.ipa,
                            score = result.score,
                            accuracy = result.accuracy,
                            timings = analysis.timings.orEmpty(),
                        ),
                    )
                }
            }

            // Practice plan built from the reviewed content bank. The client sends
            // its phonetic profile and optional theme selection; nothing is
            // generated here, so the learner never sees unreviewed text.
            rateLimit(RateLimitName("score")) {
                post("/drills") {
                    val request = call.receive<DrillRequest>()
                    val pack = LanguagePacks.forScoring(request.language)
                    val bank = state.drills[pack.code] ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Content is not available for '${pack.code}'"),
                    )
                    if (request.profile.stats.isEmpty()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("profile must contain at least one sound"),
                        )
                    }
                    val plan = DrillPlanner.plan(
                        profile = request.profile,
                        bank = bank,
                        limit = request.limit.coerceIn(1, 10),
                        themes = request.themes.toSet(),
                        targets = request.targets.toSet(),
                        level = request.level,
                        seenIds = request.seenIds.toSet(),
                        knownThemes = state.manifests[pack.code]?.themes.orEmpty(),
                    )
                    call.respond(plan)
                }
            }

            // Content routes. The catalog lets the app fetch weekly deltas;
            // scoped to one language via `?language=` (English default). Both
            // share a per-IP budget (SEC-13).
            rateLimit(RateLimitName("content")) {
                get("/content/manifest") {
                    val pack = LanguagePacks.forScoring(call.request.queryParameters["language"])
                    val manifest = state.manifests[pack.code] ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Content manifest is not available for '${pack.code}'"),
                    )
                    // Content changes between releases, so the catalog is never
                    // cached blind: the client revalidates with the ETag and gets
                    // a cheap 304 until the version actually changes. The revision
                    // suffix changes when the manifest's served shape changes (it
                    // gained per-asset sizes), so a warm cache picks it up even when
                    // `assetVersion` did not move.
                    val etag = "\"${manifest.assetVersion.ifBlank { manifest.version.toString() }}-$MANIFEST_ETAG_REVISION\""
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.response.header(HttpHeaders.ETag, etag)
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    if (ifNoneMatch != null &&
                        ifNoneMatch.split(",").any { it.trim() == etag || it.trim() == "*" }
                    ) {
                        call.respond(HttpStatusCode.NotModified)
                    } else {
                        call.respond(manifest)
                    }
                }

                // Content by kind (stories, dialogues, articles, words, exercises,
                // lessons) for one language. Passed through as stored, with
                // reference IPA and audio.
                get("/content/{kind}") {
                    val kind = call.parameters["kind"]?.uppercase()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("kind is required"))
                    if (kind !in CONTENT_KINDS) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("unknown kind"))
                    }
                    val pack = LanguagePacks.forScoring(call.request.queryParameters["language"])
                    val ids = call.request.queryParameters["ids"]
                        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    val lemmas = call.request.queryParameters["lemmas"]
                        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    val items = ContentDb.raw(
                        File(contentDir(assetsDir, pack), "content.db"),
                        kind = kind,
                        theme = call.request.queryParameters["theme"],
                        level = call.request.queryParameters["level"],
                        ref = call.request.queryParameters["ref"],
                        ids = ids.takeIf { it.isNotEmpty() },
                        lemmas = lemmas.takeIf { it.isNotEmpty() },
                        limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50,
                    ) ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Content is not available for '${pack.code}'"),
                    )
                    // A versioned URL (`?v=<assetVersion>`) points at content that
                    // never changes, so it can be cached forever and served offline.
                    // An unversioned caller may see content change in place, so it
                    // gets no-store rather than a stale entry.
                    val versioned = !call.request.queryParameters["v"].isNullOrBlank()
                    call.response.header(
                        HttpHeaders.CacheControl,
                        if (versioned) "public, max-age=31536000, immutable" else "no-store",
                    )
                    call.respondText(
                        "[" + items.joinToString(",") + "]",
                        ContentType.Application.Json,
                    )
                }
            }

            rateLimit(RateLimitName("events")) {
                post("/events") {
                    val store = analytics ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Analytics is not configured"),
                    )
                    val request = call.receive<AnalyticsIngestRequest>()
                    if (request.events.isEmpty() || request.events.size > MAX_EVENTS) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("events must contain 1..$MAX_EVENTS items"),
                        )
                    }
                    val invalid = request.events.firstOrNull {
                        it.name.isBlank() || it.name.length > MAX_EVENT_NAME ||
                            it.properties.toString().length > MAX_PROPERTIES
                    }
                    if (invalid != null) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("event name or properties are invalid or too large"),
                        )
                    }
                    val headerDeviceId = call.request.headers["X-Device-Id"]?.take(MAX_ID)
                    val now = Instant.now()
                    val events = request.events.mapNotNull { event ->
                        val name = event.name.trim()
                        ClientEvents.sanitize(name, event.properties)?.let { properties ->
                            event.copy(name = name, properties = properties)
                                .toStored(headerDeviceId, now, request.sentAt)
                        }
                    }
                    val dropped = request.events.size - events.size
                    if (events.isEmpty()) {
                        return@post call.respond(HttpStatusCode.Accepted, AnalyticsIngestResponse(0, dropped))
                    }
                    val accepted = try {
                        withContext(Dispatchers.IO) { store.insert(events) }
                    } catch (e: Exception) {
                        logger.error(e) { "analytics ingest failed" }
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Analytics storage is unavailable"),
                        )
                    }
                    eventsIngested.increment(accepted.toDouble())
                    if (mirror != null) {
                        analyticsScope.launch {
                            runCatching { mirror.send(events) }
                                .onFailure { logger.warn { "umami mirror failed: ${it.message}" } }
                        }
                    }
                    call.respond(HttpStatusCode.Accepted, AnalyticsIngestResponse(accepted, dropped))
                }

                // "Delete my usage data": every event of this install goes. The
                // install id is also the user's identity (plan, Beta Founder),
                // so it is erased from analytics instead of being regenerated.
                delete("/events") {
                    val store = analytics ?: return@delete call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Analytics is not configured"),
                    )
                    val deviceId = call.request.headers["X-Device-Id"]?.trim()?.take(MAX_ID)
                        ?.takeIf { it.isNotEmpty() }
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("X-Device-Id is required"),
                        )
                    val deleted = try {
                        withContext(Dispatchers.IO) {
                            // The dashboard copy first: if it cannot be erased, nothing is
                            // and the learner's retry deletes both.
                            (mirror?.erase(deviceId) ?: 0) + store.deleteDevice(deviceId)
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "analytics delete failed" }
                        return@delete call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Analytics storage is unavailable"),
                        )
                    }
                    call.respond(AnalyticsDeleteResponse(deleted))
                }

                post("/crash-traces") {
                    val store = analytics ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Analytics is not configured"),
                    )
                    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (declared != null && declared > CrashTraceSanitizer.MAX_BODY_BYTES) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("request body exceeds limit"),
                        )
                    }
                    val rawBody = call.receiveText()
                    if (rawBody.length > CrashTraceSanitizer.MAX_BODY_BYTES) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("request body exceeds limit"),
                        )
                    }
                    val dto = try {
                        requestJson.decodeFromString<CrashTraceDto>(rawBody)
                    } catch (e: Exception) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid JSON payload"),
                        )
                    }
                    val traceId = CrashTraceSanitizer.sanitizeShortId(dto.traceId)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("trace_id is required and must be 1..64 identifier characters"),
                        )
                    val platform = dto.platform?.trim()?.lowercase()
                    if (platform == null || platform !in CrashTraceSanitizer.PLATFORMS) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("platform must be android or ios"),
                        )
                    }
                    val exception = CrashTraceSanitizer.sanitizeCodeIdentifier(dto.exception)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("exception is required and must be a code identifier"),
                        )
                    val rawKind = dto.kind?.trim()?.takeIf { it.isNotBlank() } ?: "crash"
                    val kind = CrashTraceSanitizer.sanitizeShortId(rawKind)
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("kind is invalid"),
                        )
                    val where = CrashTraceSanitizer.sanitizeCodeIdentifier(dto.where)
                    val headerDeviceId = call.request.headers["X-Device-Id"]?.trim()?.take(MAX_ID)
                    val deviceId = CrashTraceSanitizer.sanitizeShortId(dto.deviceId ?: headerDeviceId)
                    val appVersion = CrashTraceSanitizer.sanitizeShortId(dto.appVersion)

                    val sanitizedCauses = dto.causes.take(CrashTraceSanitizer.MAX_CAUSES).map { cause ->
                        val sanitizedType = CrashTraceSanitizer.sanitizeCodeIdentifier(cause.type)
                            ?: "UnknownException"
                        val sanitizedFrames = cause.frames.take(CrashTraceSanitizer.MAX_FRAMES_PER_CAUSE).mapNotNull { frame ->
                            val className = CrashTraceSanitizer.sanitizeCodeIdentifier(frame.className)
                            val methodName = CrashTraceSanitizer.sanitizeCodeIdentifier(frame.methodName)
                            val fileName = CrashTraceSanitizer.sanitizeCodeIdentifier(frame.fileName)
                            val line = CrashTraceSanitizer.sanitizeLine(frame.line)
                            if (className == null && methodName == null && fileName == null && line == null) {
                                null
                            } else {
                                StackFrame(className = className, methodName = methodName, fileName = fileName, line = line)
                            }
                        }
                        CrashCause(type = sanitizedType, frames = sanitizedFrames)
                    }

                    val trace = StoredCrashTrace(
                        traceId = traceId,
                        deviceId = deviceId,
                        appVersion = appVersion,
                        platform = platform,
                        fatal = dto.fatal,
                        kind = kind,
                        exception = exception,
                        where = where,
                        causes = sanitizedCauses,
                    )

                    try {
                        withContext(Dispatchers.IO) { store.crashTraces.insert(trace) }
                    } catch (e: Exception) {
                        logger.error(e) { "crash trace ingest failed" }
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Analytics storage is unavailable"),
                        )
                    }

                    call.respond(HttpStatusCode.Accepted, CrashTraceResponse(accepted = true, traceId = traceId))
                }

                // A learner asks for a theme the bank does not cover yet. It becomes
                // an event, so the content team sees demand and ships it weekly.
                post("/themes/suggest") {
                    val store = analytics ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Analytics is not configured"),
                    )
                    val request = call.receive<ThemeSuggestionRequest>()
                    val theme = request.theme.trim()
                    if (theme.isEmpty() || theme.length > MAX_EVENT_NAME) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("theme must be 1..$MAX_EVENT_NAME characters"),
                        )
                    }
                    val note = request.note?.trim()?.take(MAX_NOTE)
                    val headerDeviceId = call.request.headers["X-Device-Id"]?.take(MAX_ID)
                    val properties = buildJsonObject {
                        put("theme", JsonPrimitive(theme))
                        note?.takeIf { it.isNotEmpty() }?.let { put("note", JsonPrimitive(it)) }
                        put("level", JsonPrimitive(request.level ?: ""))
                        put(
                            "weakSounds",
                            JsonPrimitive(request.weakSounds.take(10).joinToString(",")),
                        )
                    }.toString()
                    val accepted = try {
                        withContext(Dispatchers.IO) {
                            store.insert(
                                listOf(
                                    AnalyticsEvent(
                                        name = "theme_suggested",
                                        deviceId = (request.deviceId ?: headerDeviceId)?.take(MAX_ID),
                                        language = request.language.take(MAX_ID),
                                        propertiesJson = properties,
                                    ),
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "theme suggestion failed" }
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Analytics storage is unavailable"),
                        )
                    }
                    call.respond(HttpStatusCode.Accepted, ThemeSuggestionResponse(accepted > 0))
                }
            }

            // Machine-readable product analytics summary (token protected).
            get("/events/summary") {
                if (!call.adminAuthorized(adminToken)) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val store = analytics ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Analytics is not configured"),
                )
                val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
                call.respond(store.summary(days))
            }

            // Activation funnel and day 1/7/30 retention from the app's events.
            get("/events/engagement") {
                if (!call.adminAuthorized(adminToken)) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val store = analytics ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Analytics is not configured"),
                )
                val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
                call.respond(store.engagement(days))
            }

            // Machine-readable product rollup (scores, mastery, top sound errors).
            get("/events/product") {
                if (!call.adminAuthorized(adminToken)) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val store = analytics ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Analytics is not configured"),
                )
                val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
                call.respond(store.productSummary(days))
            }

            // The ingest seam of the adaptive engine. The on-device scorer
            // reports the learner's aggregate per-sound stats, keyed by an
            // anonymous install id; no audio and no personal data ever cross this
            // boundary (see docs/NEED-ENGINE.md). It persists the profile and
            // derives needs, but never aggregates or generates, so the call is
            // cheap and bounded and cannot block the session.
            rateLimit(RateLimitName("profiles")) {
                post("/profiles/sync") {
                    // Fail closed, and check the credential first: an anonymous
                    // caller must not learn whether the control plane is
                    // configured, and must never reach the pipeline that turns
                    // injected demand into paid TTS work. The check is inside the
                    // rate-limited route so guesses are counted against the IP.
                    if (!call.ingestAuthorized(ingestToken)) {
                        return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized"),
                        )
                    }
                    val learnerService = profileService
                    val needs = needService
                    if (learnerService == null || needs == null) {
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse("Profile sync is not configured"),
                        )
                    }
                    val body = parseSyncBody(call.receiveText())
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid JSON body"),
                        )
                    findForbiddenSyncKey(body)?.let { key ->
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Profile sync must not contain audio or personal data ('$key')"),
                        )
                    }
                    val request = runCatching {
                        requestJson.decodeFromJsonElement(ProfileSyncRequest.serializer(), body)
                    }.getOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid profile sync body"),
                        )
                    validateSyncRequest(request)?.let { problem ->
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                    }
                    val language = LanguagePacks.forScoring(request.language).code
                    val derived = withContext(Dispatchers.IO) {
                        val synced = learnerService.sync(request.toInput(language))
                        needs.deriveAndStore(synced.userId, language)
                    }
                    call.respond(
                        ProfileSyncResponse(accepted = true, needs = derived.map { it.toRef() }),
                    )
                }
            }

            // Admin: run the Demand Aggregator now instead of waiting for the
            // scheduler. Token protected; it only reads open needs and writes the
            // anonymous snapshot.
            post("/admin/demand/aggregate") {
                if (!call.adminAuthorized(adminToken)) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val service = demandService ?: return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Demand aggregation is not configured"),
                )
                val language = call.request.queryParameters["language"]
                val run = withContext(Dispatchers.IO) { service.aggregate(language) }
                call.respond(
                    DemandAggregateResponse(
                        needClasses = run.needClasses,
                        needs = run.needs,
                        learners = run.learners,
                        belowMinimum = run.belowMinimum,
                    ),
                )
            }

            // Admin: the anonymous demand snapshot, so "why these exercises" and
            // "which needs are growing" can be answered from data.
            get("/admin/need-classes") {
                if (!call.adminAuthorized(adminToken)) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val service = demandService ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Demand aggregation is not configured"),
                )
                val language = call.request.queryParameters["language"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val classes = withContext(Dispatchers.IO) { service.needClasses(language, limit) }
                call.respond(NeedClassListResponse(poolConfig.toDto(), classes.map { it.toDto() }))
            }

            // Admin: force a generation cycle now (aggregate demand, enqueue the
            // gaps) and drain the worker once, so an operator can see the pool
            // move without waiting for the scheduler. Never on the user path.
            post("/admin/generation/run") {
                if (!call.adminAuthorized(adminToken)) {
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val runtime = generationRuntime ?: return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Generation is not configured"),
                )
                val run = withContext(Dispatchers.IO) {
                    val enqueued = runtime.scheduler.runOnce()
                    runtime.worker.runOnce()
                    enqueued
                }
                call.respond(
                    GenerationRunResponse(
                        classesConsidered = run.classesConsidered,
                        jobsCreated = run.jobsCreated,
                        messagesEnqueued = run.messagesEnqueued,
                        classesWithoutCandidates = run.classesWithoutCandidates,
                        alreadyCovered = run.alreadyCovered,
                    ),
                )
            }

            // Admin: the generation jobs, including which provider failed and why.
            get("/admin/generation/jobs") {
                if (!call.adminAuthorized(adminToken)) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val store = generationStore ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Generation is not configured"),
                )
                val status = call.request.queryParameters["status"]?.uppercase()?.let {
                    runCatching { JobStatus.valueOf(it) }.getOrElse {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Unknown job status '$it'"),
                        )
                    }
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val jobs = withContext(Dispatchers.IO) { store.jobs(status, limit) }
                call.respond(GenerationJobsResponse(jobs.map { it.toDto() }))
            }

            // Admin: queue depth and job counts, the phase-2 health view.
            get("/admin/generation/status") {
                if (!call.adminAuthorized(adminToken)) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val runtime = generationRuntime ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Generation is not configured"),
                )
                val counts = withContext(Dispatchers.IO) { runtime.store.jobCounts() }
                call.respond(
                    GenerationStatusResponse(
                        queue = runtime.queue.id,
                        depth = withContext(Dispatchers.IO) { runtime.queue.depth() },
                        pending = withContext(Dispatchers.IO) { runtime.queue.pendingCount() },
                        jobs = counts.mapKeys { it.key.name },
                    ),
                )
            }

            // Admin: the generated content pool, newest first. "Why these
            // exercises" answered from data.
            get("/admin/content-pool") {
                if (!call.adminAuthorized(adminToken)) {
                    return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val store = generationStore ?: return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Generation is not configured"),
                )
                val language = call.request.queryParameters["language"]
                val status = call.request.queryParameters["status"]?.uppercase()?.let { name ->
                    runCatching { ContentStatus.valueOf(name) }.getOrElse {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Unknown content status '$name'"),
                        )
                    }
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val items = withContext(Dispatchers.IO) {
                    store.contentPool(language, status, limit).map { piece ->
                        val variant = store.variantsForContent(piece.id).firstOrNull()
                        piece.toDto(variant?.text, variant?.audioKey, variant?.slowAudioKey)
                    }
                }
                call.respond(ContentPoolResponse(items))
            }

            // Server-side purchase verification. The client sends the Play
            // purchase token; the server validates it against the Google Play
            // Developer API (Android Publisher) with a service account and caches
            // the verdict. A client alone cannot be the source of truth for
            // entitlement, so if the server cannot verify it answers 503 and
            // never grants Pro. Contract: BillingVerifyRequest /
            // BillingVerifyResponse. See docs/BILLING-E2E.md.
            rateLimit(RateLimitName("billing")) {
                post("/billing/verify") {
                    val request = try {
                        call.receive<BillingVerifyRequest>()
                    } catch (e: Exception) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid verification request body"),
                        )
                    }
                    when (val outcome = billing.verify(request)) {
                        is VerifyOutcome.Verdict -> {
                            persistEntitlement(
                                call,
                                request,
                                outcome.response,
                                outcome.autoRenew,
                                entitlementStore,
                            )
                            call.respond(outcome.response)
                        }
                        is VerifyOutcome.Invalid ->
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message))
                        is VerifyOutcome.Unavailable ->
                            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(outcome.message))
                    }
                }
            }

            // Real-time Developer Notifications. Google pushes a PubSubPushEnvelope
            // here on renewals, holds, grace, cancellations and refunds; the server
            // decodes its base64 DeveloperNotification, invalidates the cached
            // verdict and refreshes the state from Play. Protected by a shared
            // secret Pub/Sub must present, and optionally pinned to one push
            // subscription. Contract: PubSubPushEnvelope. See docs/BILLING-E2E.md.
            post("/billing/rtdn") {
                if (!billing.rtdnEnabled) {
                    return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("RTDN webhook is not configured. See docs/BILLING-E2E.md."),
                    )
                }
                // Header only. A shared secret in the query string is written
                // verbatim by every proxy's access log (SEC-08), so the query
                // form is gone: Pub/Sub pushes its OIDC token in the
                // `Authorization: Bearer` header. Any `?token=` is ignored, not
                // silently accepted.
                val provided = call.bearerToken()
                if (!billing.rtdnAuthorized(provided)) {
                    recordAuthFailure("rtdn")
                    return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                }
                val envelope = try {
                    call.receive<PubSubPushEnvelope>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid Pub/Sub envelope"),
                    )
                }
                when (val outcome = billing.handleRtdn(envelope)) {
                    is RtdnOutcome.Accepted -> {
                        persistRtdnEntitlement(outcome, entitlementStore)
                        call.respond(HttpStatusCode.OK, mapOf("accepted" to true))
                    }
                    is RtdnOutcome.Rejected ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message))
                }
            }
        }

        // Human-readable product analytics page (token protected). This one page
        // accepts the token in the query, because a browser cannot set the
        // Authorization header; every machine endpoint is header-only.
        get("/admin/events") {
            if (!call.adminAuthorized(adminToken, allowQuery = true)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            }
            call.response.header("X-Robots-Tag", "noindex, nofollow")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val store = analytics ?: return@get call.respond(HttpStatusCode.NotFound)
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            call.respondText(
                renderDashboardHtml(store.summary(days), store.productSummary(days), store.engagement(days)),
                ContentType.Text.Html,
            )
        }

        get("/admin/crashes") {
            if (!call.adminAuthorized(adminToken, allowQuery = true)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            }
            call.response.header("X-Robots-Tag", "noindex, nofollow")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val store = analytics ?: return@get call.respond(HttpStatusCode.NotFound)
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            val tokenParam = if (call.request.queryParameters["token"] != null) adminToken else null
            val groups = store.crashTraces.groups(days)
            val recent = store.crashTraces.list(days, limit = 50)
            call.respondText(
                renderCrashesHtml(groups, recent, days, tokenParam),
                ContentType.Text.Html,
            )
        }

        get("/admin/crashes.json") {
            if (!call.adminAuthorized(adminToken, allowQuery = false)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val store = analytics ?: return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Analytics is not configured"),
            )
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            val traces = store.crashTraces.list(days)
            call.respond(traces)
        }

        get("/admin/crashes/{traceId}") {
            if (!call.adminAuthorized(adminToken, allowQuery = true)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            }
            call.response.header("X-Robots-Tag", "noindex, nofollow")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val store = analytics ?: return@get call.respond(HttpStatusCode.NotFound)
            val traceId = call.parameters["traceId"] ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("traceId is required"),
            )
            val trace = store.crashTraces.get(traceId) ?: return@get call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse("Crash trace not found"),
            )
            val tokenParam = if (call.request.queryParameters["token"] != null) adminToken else null
            call.respondText(
                renderCrashDetailHtml(trace, tokenParam),
                ContentType.Text.Html,
            )
        }

        get("/stats") {
            if (!call.adminAuthorized(adminToken)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
            }
            call.response.header("X-Glosso-Version", SERVER_VERSION)
            call.respond(
                mapOf(
                    "inFlight" to inFlight.get(),
                    "maxConcurrent" to maxConcurrent,
                    "availableSlots" to inferenceSlots.availablePermits,
                ),
            )
        }

        if (assetsDir.isDirectory) {
            assetRoutes(assetsDir)
        }
    }
}

/**
 * Serves the heavy assets with byte-range support, so a client that lost its
 * connection can resume a download (the ~600 MB model, a lesson's audio and art)
 * instead of starting from zero. Ktor 3's static content no longer handles
 * ranges, so this route speaks `Accept-Ranges`/`Content-Range` itself and
 * streams from disk, never buffering the file in memory.
 */
private fun io.ktor.server.routing.Routing.assetRoutes(assetsDir: File) {
    val root = assetsDir.canonicalFile
    rateLimit(RateLimitName("assets")) {
        get("/assets/{path...}") {
            val rel = (call.parameters.getAll("path") ?: emptyList()).joinToString("/")
            // The assets tree holds the model, vocabulary and content, never .env,
            // .git or build output. Refuse any hidden path segment (and its .sha256
            // sidecar) so a stray dotfile in the bind mount can never be served.
            if (rel.split('/').any { it.length > 1 && it.startsWith(".") }) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            val file = File(assetsDir, rel)
            val canonical = runCatching { file.canonicalFile }.getOrNull()
            if (canonical != null && canonical.isFile && canonical.inside(root)) {
                return@get call.respondAssetFile(canonical)
            }
            // `<asset>.sha256` publishes the asset's digest so a client can verify a
            // download against something other than the length the same server just
            // claimed. Computed once per file and cached.
            if (rel.endsWith(".sha256")) {
                val base = File(assetsDir, rel.removeSuffix(".sha256"))
                val canonicalBase = runCatching { base.canonicalFile }.getOrNull()
                if (canonicalBase != null && canonicalBase.isFile && canonicalBase.inside(root)) {
                    return@get call.respondText(sha256Hex(canonicalBase), ContentType.Text.Plain)
                }
            }
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

private fun File.inside(root: File): Boolean =
    path == root.path || path.startsWith(root.path + File.separator)

private val sha256Cache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun sha256Hex(file: File): String = sha256Cache.getOrPut(file.path) {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * The hash stored and looked up for a Play purchase token. [BillingService]
 * validates and caches `purchaseToken.trim()`, so the hash must normalize the
 * same way: hashing the raw request would let a token with surrounding spaces
 * grant Pro while storing a hash the RTDN lookup can never match, breaking the
 * refund fail-closed path.
 */
private fun purchaseTokenHash(token: String): String = sha256Hex(token.trim())

internal data class ByteRange(val start: Long, val endInclusive: Long)

private suspend fun io.ktor.server.application.ApplicationCall.respondAssetFile(file: File) {
    val total = file.length()
    val etag = "\"${total}-${file.lastModified()}\""
    response.header(HttpHeaders.AcceptRanges, "bytes")
    response.header(HttpHeaders.ETag, etag)
    val contentType = contentTypeFor(file)

    // Conditional GET: a client that already holds this exact version (an image
    // loader, say) gets a cheap 304 instead of the bytes. A Range request skips
    // this and goes through If-Range below.
    if (request.headers[HttpHeaders.Range] == null &&
        request.headers[HttpHeaders.IfNoneMatch] == etag
    ) {
        respond(HttpStatusCode.NotModified)
        return
    }

    val range = parseByteRange(
        header = request.headers[HttpHeaders.Range],
        total = total,
        ifRange = request.headers[HttpHeaders.IfRange],
        etag = etag,
    )
    if (range == null) {
        respondOutputStream(contentType, HttpStatusCode.OK, total) { file.inputStream().use { it.copyTo(this) } }
        return
    }
    val length = range.endInclusive - range.start + 1
    response.header(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endInclusive}/$total")
    respondOutputStream(contentType, HttpStatusCode.PartialContent, length) {
        file.inputStream().use { input ->
            // FileInputStream.skip may skip fewer bytes than asked; loop until
            // the range start is reached or the file ends.
            var toSkip = range.start
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }
            var remaining = length
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == -1) break
                write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}

/** A single `bytes=start-end` range, or null to send the whole file. */
internal fun parseByteRange(header: String?, total: Long, ifRange: String?, etag: String): ByteRange? {
    if (header == null || !header.startsWith("bytes=")) return null
    // If-Range: when the file changed, ignore the range and send it whole.
    if (!ifRange.isNullOrBlank() && ifRange != etag) return null
    val spec = header.removePrefix("bytes=").substringBefore(',').trim()
    val startPart = spec.substringBefore('-').trim()
    val endPart = spec.substringAfter('-', "").trim()
    val start = startPart.toLongOrNull() ?: return null
    if (start < 0 || start >= total) return null
    val end = endPart.toLongOrNull()?.coerceIn(start, total - 1) ?: (total - 1)
    return if (end < start) null else ByteRange(start, end)
}

private fun contentTypeFor(file: File): ContentType = when (file.extension.lowercase()) {
    "json" -> ContentType.Application.Json
    "ogg" -> ContentType("audio", "ogg")
    "wav" -> ContentType("audio", "wav")
    "mp3" -> ContentType("audio", "mpeg")
    "png" -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "svg" -> ContentType("image", "svg+xml")
    "txt", "dict" -> ContentType.Text.Plain
    else -> ContentType.Application.OctetStream
}

/**
 * Gate for the admin surfaces. The token travels in `Authorization: Bearer`;
 * the query fallback is opt-in ([allowQuery]) and only for the human HTML
 * dashboard, which a browser cannot authenticate with a header, because a token
 * in a URL leaks to proxy and referrer logs (SEC-08).
 */
private fun io.ktor.server.application.ApplicationCall.adminAuthorized(
    token: String?,
    allowQuery: Boolean = false,
): Boolean {
    val ok = token.isNullOrEmpty().not() && run {
        val header = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val provided = header?.takeIf { it.isNotEmpty() }
            ?: if (allowQuery) request.queryParameters["token"]?.takeIf { it.isNotEmpty() } else null
        if (provided.isNullOrEmpty()) return@run false
        MessageDigest.isEqual(provided.toByteArray(), token.toByteArray())
    }
    if (!ok) recordAuthFailure("admin")
    return ok
}

/**
 * The credential a caller presented in the `Authorization: Bearer` header, or
 * null. Only this form is trusted for machine endpoints: a secret in a query
 * string is written by every proxy access log (SEC-08).
 */
private fun io.ktor.server.application.ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Persists the Play verdict in `glosso.entitlements`, attributed to the device
 * that sent the token. Best-effort: a database hiccup must never change the
 * verdict the caller already earned, so the write runs in [runCatching] and a
 * failure is logged, not surfaced.
 */
private suspend fun persistEntitlement(
    call: io.ktor.server.application.ApplicationCall,
    request: BillingVerifyRequest,
    response: BillingVerifyResponse,
    autoRenew: Boolean,
    store: EntitlementStore?,
) {
    if (store == null) return
    val deviceId = (request.deviceId ?: call.request.headers["X-Device-Id"])
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
        ?: return
    runCatching {
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val user = store.upsertUser(deviceId, now).identity
            val existing = store.entitlement(user.id)
            val tokenHash = purchaseTokenHash(request.purchaseToken)
            // A PERSONALIZED plan is granted outside Play. A non-entitled Play
            // verdict for a different purchase must not take it away; only the
            // purchase this row already records may revoke it. Without this,
            // any expired or foreign token would wipe a manual grant.
            if (!response.entitled &&
                existing?.plan == "PERSONALIZED" &&
                existing.providerTokenHash != tokenHash
            ) {
                return@withContext
            }
            store.upsertEntitlement(
                StoredEntitlement(
                    userId = user.id,
                    // A paid verdict must not downgrade a PERSONALIZED plan.
                    plan = if (response.entitled) existing?.providerPlan() ?: "PRO" else "FREE",
                    status = response.status,
                    source = "play",
                    expiresAt = response.expiresAtMillis?.let(Instant::ofEpochMilli),
                    // A repeated verify records the subscription's start, not
                    // the moment of the last lookup.
                    startedAt = existing?.startedAt ?: now,
                    autoRenew = autoRenew,
                    provider = "google_play",
                    providerTokenHash = tokenHash,
                ),
            )
        }
    }.onFailure { logger.warn(it) { "billing entitlement persistence failed" } }
}

/**
 * Persists an RTDN verdict for the user who verified that purchase. RTDN carries
 * no device id, so the row is matched by the hash of the purchase token stored at
 * verification time. A revoked or voided refund therefore denies entitlement in
 * the table at once, not only in the cache. Best-effort, like
 * [persistEntitlement]: the webhook is already acknowledged to Pub/Sub.
 */
private suspend fun persistRtdnEntitlement(outcome: RtdnOutcome.Accepted, store: EntitlementStore?) {
    val token = outcome.purchaseToken ?: return
    val verdict = outcome.entitlement ?: return
    if (store == null) return
    runCatching {
        withContext(Dispatchers.IO) {
            store.updateEntitlementsByProviderTokenHash(
                providerTokenHash = purchaseTokenHash(token),
                entitled = verdict.entitled,
                status = verdict.status.name,
                expiresAt = verdict.expiresAtMillis?.let(Instant::ofEpochMilli),
                autoRenew = verdict.autoRenew,
            )
        }
    }.onFailure { logger.warn(it) { "rtdn entitlement persistence failed" } }
}

/**
 * True when the request reached us over TLS. Caddy terminates TLS and forwards
 * `X-Forwarded-Proto`, so HSTS is only emitted when the client-facing hop was
 * https; sending it over plain http would be ignored by browsers anyway and
 * would be wrong to advertise.
 */
private fun io.ktor.server.application.ApplicationCall.isSecureRequest(): Boolean {
    if (request.origin.scheme == "https") return true
    val forwarded = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()
    return forwarded.equals("https", ignoreCase = true)
}

/**
 * The single access-log line. Deliberately built from `path()` only, never
 * `uri`/query and never a header, and control characters are stripped so a
 * crafted path cannot forge extra log lines. See A09 in docs/OWASP-BACKEND.md.
 */
internal fun io.ktor.server.application.ApplicationCall.accessLogLine(): String {
    val status = response.status()?.value ?: 0
    return "$status ${request.httpMethod.value} ${sanitizeLogText(request.path())}"
}

/** Removes control characters (newlines included) and caps a log-bound string. */
internal fun sanitizeLogText(value: String, max: Int = 512): String {
    val cleaned = buildString(value.length.coerceAtMost(max)) {
        for (ch in value) {
            if (length >= max) break
            if (ch.code < 0x20 || ch.code == 0x7f) continue
            append(ch)
        }
    }
    return cleaned
}

/**
 * The rate-limit key. Trusts only [EDGE_CLIENT_IP_HEADER], which the Caddy edge
 * writes from `{client_ip}` after `trusted_proxies` + `trusted_proxies_strict`
 * resolved the real client. Never `X-Forwarded-For`: behind Cloudflare the
 * leftmost XFF value is whatever the caller sent, and Cloudflare appends the
 * real one to its right. Falls back to the socket peer, so a request that
 * reaches the app without the edge is still keyed by its real address.
 */
/** The trimmed `X-Device-Id` header, or null when missing or out of bounds. */
private fun io.ktor.server.application.ApplicationCall.deviceIdOrNull(): String? =
    request.headers["X-Device-Id"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }

private fun io.ktor.server.application.ApplicationCall.clientLimitKey(): String {
    val edge = request.headers[EDGE_CLIENT_IP_HEADER]?.trim()
    if (!edge.isNullOrEmpty() && edge.length <= 64) return edge
    return request.origin.remoteHost
}

/**
 * Gate for the profile ingest service credential. Unlike [apiKeyAuthorized] this
 * fails closed: a missing or blank configured token rejects every caller. The
 * ingest is a server-to-server seam that feeds the generation queue, so it can
 * never be left open by accident. Comparison is constant time.
 */
private fun io.ktor.server.application.ApplicationCall.ingestAuthorized(token: String?): Boolean {
    val expected = token?.trim()?.takeIf { it.isNotEmpty() }
    val provided = request.headers[INGEST_TOKEN_HEADER]?.trim()?.takeIf { it.isNotEmpty() }
    val ok = expected != null && provided != null &&
        MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
    if (!ok) recordAuthFailure("ingest")
    return ok
}

/**
 * Gate for the client scoring credential. A null key means "not configured", so
 * development builds and the emulator keep working; production sets
 * `GLOSSO_API_KEY` and every caller must send the same `X-Api-Key`.
 */
private fun io.ktor.server.application.ApplicationCall.apiKeyAuthorized(apiKey: String?): Boolean {
    // Unset key means "not configured": development builds and the emulator keep
    // working. Production sets GLOSSO_API_KEY (A07 in docs/OWASP-BACKEND.md).
    if (apiKey.isNullOrEmpty()) return true
    val provided = request.headers[API_KEY_HEADER]?.trim()?.takeIf { it.isNotEmpty() }
    val ok = provided != null && MessageDigest.isEqual(provided.toByteArray(), apiKey.toByteArray())
    if (!ok) recordAuthFailure("api_key")
    return ok
}

/** Parses a profile sync body, refusing anything larger than the sync budget. */
private fun parseSyncBody(raw: String): JsonObject? {
    if (raw.length > MAX_SYNC_BODY) return null
    return runCatching { requestJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

/**
 * Walks the raw body and returns the first forbidden key it finds, or null.
 * Matched on the key, case- and separator-insensitively, so `audioBase64` and
 * `audio_base64` are both caught. This is the hard privacy gate: a request that
 * carries the learner's audio, or personal data, is refused before it is
 * interpreted as a profile.
 */
private fun findForbiddenSyncKey(element: JsonElement): String? = when (element) {
    is JsonObject -> element.entries.firstNotNullOfOrNull { (key, value) ->
        val normalized = key.lowercase().filter { it.isLetterOrDigit() }
        if (normalized in FORBIDDEN_SYNC_KEYS) key else findForbiddenSyncKey(value)
    }
    is JsonArray -> element.firstNotNullOfOrNull { findForbiddenSyncKey(it) }
    is JsonPrimitive -> null
}

private fun validateSyncRequest(request: ProfileSyncRequest): String? {
    if (request.deviceId.isBlank() || request.deviceId.length > MAX_ID) {
        return "deviceId must be 1..$MAX_ID characters"
    }
    if ((request.cefr?.length ?: 0) > 8) return "cefr is invalid"
    if (request.phonemes.size > MAX_PHONEMES) {
        return "phonemes must contain at most $MAX_PHONEMES items"
    }
    for (phoneme in request.phonemes) {
        if (phoneme.phoneme.isBlank() || phoneme.phoneme.length > MAX_PHONEME) return "phoneme is invalid"
        val pattern = phoneme.errorPattern?.trim()
        if (!pattern.isNullOrEmpty() && !RULE_ID.matches(pattern)) return "errorPattern is invalid"
        if ((phoneme.replacementPhoneme?.length ?: 0) > MAX_PHONEME) return "replacementPhoneme is invalid"
        val position = phoneme.position?.trim()?.lowercase()
        if (!position.isNullOrEmpty() && position !in SYNC_POSITIONS) return "position is invalid"
        val trend = phoneme.trend?.trim()?.lowercase()
        if (!trend.isNullOrEmpty() && trend !in SYNC_TRENDS) return "trend is invalid"
        if (phoneme.score !in 0..100) return "score must be 0..100"
        if (phoneme.confidence < 0f || phoneme.confidence > 1f) return "confidence must be 0..1"
        if (phoneme.attempts < 0 || phoneme.successfulAttempts < 0 ||
            phoneme.successfulAttempts > phoneme.attempts
        ) {
            return "attempts are invalid"
        }
    }
    return null
}

private fun ProfileSyncRequest.toInput(language: String): ProfileSyncInput = ProfileSyncInput(
    deviceId = deviceId.trim().take(MAX_ID),
    language = language,
    cefr = cefr?.trim()?.takeIf { it.isNotEmpty() },
    vocabularyScore = vocabularyScore,
    grammarScore = grammarScore,
    comprehensionScore = comprehensionScore,
    pronunciationScore = pronunciationScore,
    fluencyScore = fluencyScore,
    speakingScore = speakingScore,
    sampleCountsJson = sampleCounts.toString(),
    phonemes = phonemes.map { it.toSnapshot() },
)

private fun PhonemeSnapshotDto.toSnapshot(): PhonemeSnapshot = PhonemeSnapshot(
    phoneme = phoneme.trim(),
    score = score.coerceIn(0, 100),
    confidence = confidence.coerceIn(0f, 1f),
    errorPattern = errorPattern?.trim()?.takeIf { it.isNotEmpty() },
    replacementPhoneme = replacementPhoneme?.trim()?.takeIf { it.isNotEmpty() },
    position = position?.trim()?.takeIf { it.isNotEmpty() },
    attempts = attempts,
    successfulAttempts = successfulAttempts,
    lastSeen = lastSeen?.let { runCatching { Instant.parse(it) }.getOrNull() },
    trend = trend?.trim()?.takeIf { it.isNotEmpty() },
)

private fun UserNeed.toRef(): NeedClassRef = NeedClassRef(
    cefr = cefr,
    skill = skill,
    phoneme = phoneme,
    errorPattern = errorPattern,
    replacementPhoneme = replacementPhoneme,
    position = position,
    priority = priority,
)

private fun NeedClass.toDto(): NeedClassDto = NeedClassDto(
    language = key.language,
    cefr = key.cefr,
    skill = key.skill,
    phoneme = key.phoneme,
    errorPattern = key.errorPattern,
    replacementPhoneme = key.replacementPhoneme,
    position = key.position,
    learners = learnerCount,
    availableExisting = availableExisting,
    availableEffective = availableEffective,
    gap = gap,
    gapState = gapState.name,
    priority = priority,
)

private fun PoolConfig.toDto(): PoolConfigDto = PoolConfigDto(minimum, target, maximum)

private fun ContentPiece.toDto(text: String?, audioKey: String?, slowAudioKey: String?): ContentPoolItemDto =
    ContentPoolItemDto(
        id = id,
        kind = kind,
        language = language,
        cefr = cefr,
        targetPhoneme = targetPhoneme,
        errorPattern = errorPattern,
        position = position,
        status = status.name,
        createdBy = createdBy,
        provider = provider,
        text = text,
        audioUrl = audioKey,
        slowAudioUrl = slowAudioKey,
    )

private fun GenerationJob.toDto(): GenerationJobDto = GenerationJobDto(
    id = id,
    task = task,
    status = status.name,
    attempts = attempts,
    maxAttempts = maxAttempts,
    provider = provider,
    model = model,
    variantId = variantId,
    contentId = contentId,
    error = error,
    requestedAt = requestedAt.toString(),
    finishedAt = finishedAt?.toString(),
)

private fun AnalyticsEventDto.toStored(headerDeviceId: String?, now: Instant, sentAt: Long?): AnalyticsEvent = AnalyticsEvent(
    name = name.trim(),
    deviceId = (deviceId ?: headerDeviceId)?.take(MAX_ID),
    sessionId = sessionId?.take(MAX_ID),
    appVersion = appVersion?.take(MAX_ID),
    platform = platform?.take(MAX_ID),
    language = language?.take(MAX_ID),
    propertiesJson = properties.toString(),
    // Dated by its age on the device when the batch has the send time, so the
    // device clock's error cancels out; else by the device clock as sent.
    occurredAt = occurredAt
        ?.let { at -> if (sentAt != null) now.minusMillis(sentAt - at) else Instant.ofEpochMilli(at) }
        ?.takeIf { it >= now - MAX_EVENT_AGE && it <= now + MAX_EVENT_SKEW },
)

/** The app sends `X-Analytics: off` when the learner turned usage statistics off. */
private fun io.ktor.server.application.ApplicationCall.analyticsOptedOut(): Boolean =
    request.headers["X-Analytics"]?.trim()?.equals("off", ignoreCase = true) == true

private fun renderDashboardHtml(
    summary: AnalyticsSummary,
    product: ProductSummary,
    engagement: EngagementSummary,
): String = buildString {
    append("<!doctype html><html><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>Glosso analytics</title><style>")
    append("body{font-family:system-ui,-apple-system,sans-serif;margin:2rem;background:#0f1115;color:#e6e6e6}")
    append("table{border-collapse:collapse;margin:1rem 0;min-width:280px}")
    append("td,th{border:1px solid #2a2f3a;padding:6px 12px;text-align:left}")
    append("th{background:#1a1f29}h1{font-weight:600}h2{font-weight:600;margin-top:2rem}")
    append(".kpis{display:flex;gap:1rem;flex-wrap:wrap;margin:1rem 0}")
    append(".kpi{background:#1a1f29;border:1px solid #2a2f3a;border-radius:10px;padding:12px 18px;min-width:120px}")
    append(".kpi .v{font-size:1.6rem;font-weight:700}.kpi .l{font-size:.75rem;color:#9aa4b2}")
    append(".bar{height:10px;background:#7c4dff;border-radius:5px;display:inline-block;vertical-align:middle}")
    append("</style></head><body>")
    append("<h1>Glosso analytics</h1>")
    append("<p>Last ${summary.days} days - ${summary.total} events from ${summary.devices} devices.</p>")

    val time = engagement.time
    append("<h2>Time in the app</h2><div class=\"kpis\">")
    append(kpi(formatDuration(time.medianSessionSeconds), "median session"))
    append(kpi(formatDuration(time.averageSessionSeconds), "average session"))
    append(kpi("${time.minutesPerActiveDay} min", "per user per active day"))
    append(kpi("${time.sessionsPerDevice}", "sessions per user"))
    append(kpi("${time.sessions}", "sessions"))
    append("</div>")

    append("<h2>Activation</h2><p>Devices that opened the app in the window (app versions that send events).</p>")
    append("<table><tr><th>step</th><th>devices</th><th>of opens</th></tr>")
    val opened = engagement.funnel.firstOrNull()?.devices ?: 0L
    for (step in engagement.funnel) {
        val share = if (opened == 0L) "-" else "${step.devices * 100 / opened}%"
        append("<tr><td>${escapeHtml(step.name)}</td><td>${step.devices}</td><td>$share</td></tr>")
    }
    append("</table><h3>Retention</h3><table><tr><th>day</th><th>came back</th><th>of</th><th></th></tr>")
    for (point in engagement.retention) {
        val share = if (point.eligible == 0L) "-" else "${point.retained * 100 / point.eligible}%"
        append("<tr><td>D${point.day}</td><td>${point.retained}</td><td>${point.eligible}</td><td>$share</td></tr>")
    }
    append("</table>")

    append("<h2>Pronunciation</h2><div class=\"kpis\">")
    append(kpi(product.attempts.toString(), "attempts"))
    append(kpi("${product.avgScore}", "avg score"))
    append(kpi("${product.masteryRate}%", "mastery rate"))
    append(kpi("${product.audioShare}%", "with audio"))
    append("</div>")

    val maxBucket = (product.scoreBuckets.maxOfOrNull { it.count } ?: 0L).coerceAtLeast(1L)
    append("<h3>Score distribution</h3><table><tr><th>band</th><th>count</th><th></th></tr>")
    for (bucket in product.scoreBuckets) {
        val width = (bucket.count * 220 / maxBucket).toInt()
        append("<tr><td>${bucket.from}-${bucket.to}</td><td>${bucket.count}</td>")
        append("<td><span class=\"bar\" style=\"width:${width}px\"></span></td></tr>")
    }
    append("</table>")

    append("<h3>Levels</h3><table><tr><th>level</th><th>count</th></tr>")
    for (level in product.levels) {
        append("<tr><td>${escapeHtml(level.name)}</td><td>${level.count}</td></tr>")
    }
    append("</table>")

    if (product.topErrors.isNotEmpty()) {
        append("<h3>Top sound errors</h3><table><tr><th>rule</th><th>count</th></tr>")
        for (error in product.topErrors) {
            append("<tr><td>${escapeHtml(error.name)}</td><td>${error.count}</td></tr>")
        }
        append("</table>")
    }

    append("<h2>Events</h2><table><tr><th>event</th><th>count</th></tr>")
    for (event in summary.byName) {
        append("<tr><td>${escapeHtml(event.name)}</td><td>${event.count}</td></tr>")
    }
    append("</table><h3>Daily</h3><table><tr><th>day</th><th>events</th><th>devices</th></tr>")
    for (day in summary.daily) {
        append("<tr><td>${escapeHtml(day.day)}</td><td>${day.events}</td><td>${day.devices}</td></tr>")
    }
    append("</table></body></html>")
}

private fun renderCrashesHtml(
    groups: List<CrashGroup>,
    recent: List<CrashTraceRecord>,
    days: Int,
    tokenParam: String?,
): String = buildString {
    val q = if (tokenParam != null) "?token=${escapeHtml(tokenParam)}" else ""
    append("<!doctype html><html><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>Glosso crash traces</title><style>")
    append("body{font-family:system-ui,-apple-system,sans-serif;margin:2rem;background:#0f1115;color:#e6e6e6}")
    append("table{border-collapse:collapse;margin:1rem 0;min-width:280px;width:100%}")
    append("td,th{border:1px solid #2a2f3a;padding:6px 12px;text-align:left}")
    append("th{background:#1a1f29}h1{font-weight:600}h2{font-weight:600;margin-top:2rem}")
    append("a{color:#7c4dff;text-decoration:none}a:hover{text-decoration:underline}")
    append(".badge{display:inline-block;padding:2px 6px;border-radius:4px;font-size:0.75rem;font-weight:600}")
    append(".badge-fatal{background:#e53935;color:#fff}")
    append(".badge-nonfatal{background:#2a2f3a;color:#9aa4b2}")
    append("</style></head><body>")
    append("<h1>Glosso crash traces</h1>")
    append("<p>Last $days days - <a href=\"/admin/events$q\">View analytics dashboard</a></p>")
    append("<h2>Crash groups (by exception + where)</h2>")
    if (groups.isEmpty()) {
        append("<p>No crash traces recorded in the last $days days.</p>")
    } else {
        append("<table><tr><th>Exception</th><th>Where</th><th>Count</th><th>Fatal</th><th>Versions</th><th>Last Seen</th><th>Action</th></tr>")
        for (g in groups) {
            val link = "/admin/crashes/${escapeHtml(g.latestTraceId)}$q"
            append("<tr>")
            append("<td><code>${escapeHtml(g.exception)}</code></td>")
            append("<td><code>${escapeHtml(g.where ?: "-")}</code></td>")
            append("<td>${g.count}</td>")
            append("<td>${g.fatalCount}</td>")
            append("<td>${escapeHtml(g.versions.joinToString(", ").ifEmpty { "-" })}</td>")
            append("<td>${escapeHtml(g.lastSeen.toString())}</td>")
            append("<td><a href=\"$link\">View trace (${escapeHtml(g.latestTraceId)})</a></td>")
            append("</tr>")
        }
        append("</table>")
    }

    if (recent.isNotEmpty()) {
        append("<h2>Recent traces</h2>")
        append("<table><tr><th>Time</th><th>Trace ID</th><th>Platform</th><th>Version</th><th>Fatal</th><th>Kind</th><th>Exception</th><th>Where</th></tr>")
        for (t in recent) {
            val link = "/admin/crashes/${escapeHtml(t.traceId)}$q"
            append("<tr>")
            append("<td>${escapeHtml(t.receivedAt)}</td>")
            append("<td><a href=\"$link\"><code>${escapeHtml(t.traceId)}</code></a></td>")
            append("<td>${escapeHtml(t.platform)}</td>")
            append("<td>${escapeHtml(t.appVersion ?: "-")}</td>")
            append("<td>${if (t.fatal) "<span class=\"badge badge-fatal\">FATAL</span>" else "<span class=\"badge badge-nonfatal\">NON-FATAL</span>"}</td>")
            append("<td>${escapeHtml(t.kind)}</td>")
            append("<td><code>${escapeHtml(t.exception)}</code></td>")
            append("<td><code>${escapeHtml(t.where ?: "-")}</code></td>")
            append("</tr>")
        }
        append("</table>")
    }
    append("</body></html>")
}

private fun renderCrashDetailHtml(
    trace: CrashTraceRecord,
    tokenParam: String?,
): String = buildString {
    val q = if (tokenParam != null) "?token=${escapeHtml(tokenParam)}" else ""
    append("<!doctype html><html><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>Crash trace ${escapeHtml(trace.traceId)}</title><style>")
    append("body{font-family:system-ui,-apple-system,sans-serif;margin:2rem;background:#0f1115;color:#e6e6e6}")
    append("table{border-collapse:collapse;margin:1rem 0;min-width:280px}")
    append("td,th{border:1px solid #2a2f3a;padding:6px 12px;text-align:left}")
    append("th{background:#1a1f29}")
    append("a{color:#7c4dff;text-decoration:none}a:hover{text-decoration:underline}")
    append(".badge{display:inline-block;padding:2px 6px;border-radius:4px;font-size:0.75rem;font-weight:600}")
    append(".badge-fatal{background:#e53935;color:#fff}")
    append(".badge-nonfatal{background:#2a2f3a;color:#9aa4b2}")
    append(".stack-box{background:#161920;border:1px solid #2a2f3a;border-radius:8px;padding:1rem;margin:1rem 0;font-family:monospace;font-size:0.85rem;line-height:1.5;overflow-x:auto}")
    append(".cause-header{color:#ff80ab;font-weight:600;margin-bottom:0.5rem}")
    append(".frame-line{color:#c5c8d4;margin-left:1.5rem}")
    append("</style></head><body>")
    append("<p><a href=\"/admin/crashes$q\">&larr; Back to crashes</a></p>")
    append("<h1>Crash trace <code>${escapeHtml(trace.traceId)}</code></h1>")
    append("<table>")
    append("<tr><th>Field</th><th>Value</th></tr>")
    append("<tr><td>Trace ID</td><td><code>${escapeHtml(trace.traceId)}</code></td></tr>")
    append("<tr><td>Received At</td><td>${escapeHtml(trace.receivedAt)}</td></tr>")
    append("<tr><td>Device ID</td><td><code>${escapeHtml(trace.deviceId ?: "-")}</code></td></tr>")
    append("<tr><td>Platform</td><td>${escapeHtml(trace.platform)}</td></tr>")
    append("<tr><td>App Version</td><td>${escapeHtml(trace.appVersion ?: "-")}</td></tr>")
    append("<tr><td>Fatal</td><td>${if (trace.fatal) "<span class=\"badge badge-fatal\">FATAL</span>" else "<span class=\"badge badge-nonfatal\">NON-FATAL</span>"}</td></tr>")
    append("<tr><td>Kind</td><td>${escapeHtml(trace.kind)}</td></tr>")
    append("<tr><td>Exception</td><td><code>${escapeHtml(trace.exception)}</code></td></tr>")
    append("<tr><td>Where</td><td><code>${escapeHtml(trace.where ?: "-")}</code></td></tr>")
    append("</table>")

    append("<h2>Stack Trace</h2>")
    if (trace.causes.isEmpty()) {
        append("<p>No stack frames recorded.</p>")
    } else {
        for ((idx, cause) in trace.causes.withIndex()) {
            append("<div class=\"stack-box\">")
            val prefix = if (idx == 0) "Exception: " else "Caused by: "
            append("<div class=\"cause-header\">${escapeHtml(prefix + cause.type)}</div>")
            if (cause.frames.isEmpty()) {
                append("<div class=\"frame-line\">(no frames)</div>")
            } else {
                for (frame in cause.frames) {
                    val location = when {
                        frame.fileName != null && frame.line != null -> "${frame.fileName}:${frame.line}"
                        frame.fileName != null -> frame.fileName
                        frame.line != null -> ":${frame.line}"
                        else -> "Unknown Source"
                    }
                    val method = listOfNotNull(frame.className, frame.methodName).joinToString(".")
                    append("<div class=\"frame-line\">at ${escapeHtml(method)}(${escapeHtml(location)})</div>")
                }
            }
            append("</div>")
        }
    }
    append("</body></html>")
}

private fun formatDuration(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

private fun kpi(value: String, label: String): String =
    "<div class=\"kpi\"><div class=\"v\">${escapeHtml(value)}</div><div class=\"l\">${escapeHtml(label)}</div></div>"

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
