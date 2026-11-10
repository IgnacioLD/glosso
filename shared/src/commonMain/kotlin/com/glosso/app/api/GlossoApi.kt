package com.glosso.app.api

import com.glosso.app.CrashTrace
import com.glosso.engine.model.ArticleModel
import com.glosso.engine.model.ContentManifest
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.DrillPlanModel
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.WordModel
import com.glosso.engine.model.forAccent
import com.glosso.engine.model.localized
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.CacheStorage
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ScoreRequest(
    val text: String,
    val expectedIpa: String? = null,
    val actualIpa: String? = null,
    val audioBase64: String? = null,
    val language: String = "en_GB",
    val uiLanguage: String = "en",
    val profile: PhoneticProfileModel? = null,
    /** The accent the learner is mastering ("en-US"); it only breaks ties between accents. */
    val accent: String? = null,
    /**
     * The item's published whole-sentence references per accent (`accentIpa` of the
     * content). A server with multi-accent scoring on scores against them; any
     * other server ignores the field.
     */
    val accentIpa: Map<String, String> = emptyMap(),
)

/** Body of GET /v1/accents: only what the client needs from it. */
@Serializable
data class AccentScoringState(val multiAccentScoring: Boolean = false)

/** Body of POST /v1/drills: a plan picked for this learner from the reviewed bank. */
@Serializable
data class DrillPlanRequest(
    val profile: PhoneticProfileModel,
    val limit: Int = 4,
    val themes: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val level: String? = null,
    val seenIds: List<String> = emptyList(),
    val language: String = "",
)

/** A non-2xx answer from an account call; [status] tells the UI what to say. */
class ApiStatusException(val status: Int) : Exception("HTTP $status")

/** A sign-in nonce bound to this device (`POST /v1/auth/google/nonce`). */
@Serializable
data class GoogleSignInNonceResponse(
    val nonce: String,
)

/** The ID token of the Google account picked on the device (`POST /v1/auth/google`). */
@Serializable
data class GoogleSignInRequest(
    val idToken: String,
)

/**
 * One usage event for `POST /v1/events`. The device id travels in the header;
 * [properties] only carries what the server's catalogue allows (enums, numbers,
 * content ids), never text the learner wrote or audio.
 */
@Serializable
data class ClientEvent(
    val name: String,
    val sessionId: String? = null,
    val appVersion: String? = null,
    val platform: String = "android",
    val language: String? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
    /** Epoch millis on the device, so a queued event keeps its time. */
    val occurredAt: Long,
)

/** [sentAt] lets the server date each event by its age, cancelling a wrong device clock. */
@Serializable
private data class ClientEventBatch(val events: List<ClientEvent>, val sentAt: Long)

/**
 * Talks to the Glosso API: content and cloud scoring.
 *
 * Every platform input is a parameter, so the class is the same on Android and
 * iOS; each app builds it with its own defaults (on Android, the `GlossoApi(...)`
 * factory in `:androidApp`).
 */
class GlossoApi(
    /**
     * The server, read at call time so the whole app follows the server setting
     * (`ServerPreferences.base`); a test passes a fixed one.
     */
    private val serverBase: () -> String,
    /** The HTTP engine; null picks the platform's own ([defaultHttpEngine]). */
    engine: HttpClientEngine?,
    /** Disk cache for immutable content; null disables HTTP caching. */
    private val contentCache: ContentCacheStorage?,
    private val versionStore: AssetVersionStore,
    /**
     * Shared client credential for the scoring oracle, from the build
     * (`glosso.apiKey` in local.properties). Empty by default: no header is
     * sent and a server without `GLOSSO_API_KEY` behaves exactly as before.
     */
    private val apiKey: String,
    /**
     * The device identity sent as `X-Device-Id` on every request. Resolved at
     * call time (not construction) because the device id is initialised by the
     * app at startup; a test without one sends no header.
     */
    private val deviceIdProvider: () -> String?,
    /**
     * Whether the learner shares usage statistics. When off, every request says
     * so and the server does not record the score event either.
     */
    private val analyticsEnabled: () -> Boolean,
    /** The learner's language and accent choices, read at call time. */
    private val scope: ContentScope,
) {

    /** The server this client last used, to notice a switch (see [baseUrl]). */
    @Volatile
    private var currentServer: String? = null

    /**
     * The server for this request. On a switch, what this client learned from the
     * previous server (the manifest and its content version) is forgotten, and
     * the content version is read again from the store `ServerSwitch` emptied.
     */
    private val baseUrl: String
        get() {
            val base = serverBase()
            if (base != currentServer) {
                if (currentServer != null) {
                    lastManifest = null
                    manifestLoaded = false
                    assetVersion = versionStore.load().orEmpty()
                }
                currentServer = base
            }
            return base
        }

    /** The target language every request and asset URL is scoped to. */
    private val language: String get() = scope.language

    /** The accent the content is worded in for this learner; see [forAccent]. */
    private val accent: String get() = scope.accent

    /** The accent the bank is written in, whose textbook IPA the content carries. */
    private val authoredAccent: String get() = scope.authoredAccent

    /** The interface language, which picks the translation of lesson and exercise text. */
    private val uiLanguage: String get() = scope.uiLanguage

    internal val client = HttpClient(engine ?: defaultHttpEngine()) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // The device identity travels on every call. The server keys the user
        // (`/v1/me`, events, scoring) by this header; a build with no id yet
        // sends no header, so the client is never half-identified.
        defaultRequest {
            deviceIdProvider()?.takeIf { it.isNotBlank() }?.let {
                headers.append(DEVICE_ID_HEADER, it)
            }
            if (!analyticsEnabled()) headers.append(ANALYTICS_HEADER, "off")
        }
        // A dead connection should surface as an error the UI can show, not hang.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        // Content and assets are immutable per version, so GET responses with
        // cache headers are reused (and survive offline); the plugin never
        // touches POST, so score and events stay uncached.
        install(HttpCache) {
            publicStorage(contentCache ?: CacheStorage.Disabled)
        }
    }

    /**
     * The catalog: which levels and themes exist, and the version of every bank.
     * Revalidated with the server's ETag, so publishing content needs no app
     * update but an unchanged catalog costs one cheap 304.
     */
    suspend fun manifest(): ContentManifest =
        client.get("$baseUrl/v1/content/manifest") {
            headers { append(HttpHeaders.CacheControl, "no-cache") }
            url { parameters.append("language", language) }
        }.body<ContentManifest>().also {
            lastManifest = it
            manifestLoaded = true
            updateAssetVersion(it.assetVersion)
        }

    /**
     * Byte size of an asset as published in the manifest, or null when the
     * server does not carry sizes (an older deployment). Lets a screen add up a
     * lesson's size from the one manifest it already fetched.
     */
    fun manifestAssetSize(path: String): Long? = lastManifest?.assets?.get(path)

    /** Sum of [paths] from the manifest, or null when any size is unpublished. */
    fun manifestSizeOf(paths: Collection<String>): Long? = lastManifest?.sizeOf(paths)

    /**
     * The product configuration: phase, beta window, plans, limits, prices,
     * feature flags and the version policy. `Cache-Control: no-cache` lets the
     * server answer 304 while its ETag is unchanged, exactly like the content
     * manifest; the repository keeps the last good body for offline starts. A
     * non-2xx answer throws [ApiStatusException] instead of decoding an error
     * body into the all-defaults fallback and persisting it.
     */
    suspend fun productConfig(): ProductConfig =
        client.get("$baseUrl/v1/config") {
            headers { append(HttpHeaders.CacheControl, "no-cache") }
        }.successBody()

    /**
     * This device's account, keyed by `X-Device-Id`. A non-2xx answer (e.g. 409
     * when the account changed meanwhile) throws [ApiStatusException] instead of
     * decoding the error body into an all-defaults account.
     */
    suspend fun me(): AccountState =
        client.get("$baseUrl/v1/me") {
            headers { append(HttpHeaders.CacheControl, "no-cache") }
        }.successBody()

    /**
     * Whether the server scores against the accent systems, so the on-device
     * scorer can do the same and both give the same score. Public, no identity.
     */
    suspend fun accentScoring(): AccentScoringState =
        client.get("$baseUrl/v1/accents") {
            headers { append(HttpHeaders.CacheControl, "no-cache") }
        }.successBody()

    suspend fun drills(limit: Int = 10, level: String? = null): List<DrillModel> =
        client.get("$baseUrl/v1/content/DRILL") {
            versioned()
            url {
                parameters.append("language", language)
                level?.let { parameters.append("level", it) }
                parameters.append("limit", limit.toString())
            }
        }.body<List<DrillModel>>().map { it.forAccent(accent, authoredAccent) }

    suspend fun score(request: ScoreRequest): PronunciationFeedback {
        val response = client.post("$baseUrl/v1/score") {
            noCache()
            contentType(ContentType.Application.Json)
            // Only sent when the build carries a key; an empty key means an
            // open server and no header, exactly the old behaviour.
            if (apiKey.isNotBlank()) headers { append(API_KEY_HEADER, apiKey) }
            setBody(request)
        }
        // The engine rejects recordings with no usable speech. The API answers
        // 422 for that - and its tiny body would otherwise deserialize into an
        // empty feedback (everything defaults), i.e. a bogus 0/100. Surface it
        // as a not-evaluated attempt so the learner is asked to retry instead.
        if (response.status == HttpStatusCode.UnprocessableEntity) {
            return PronunciationFeedback(evaluated = false)
        }
        return response.body()
    }

    /** A practice plan for this learner: weakest sound first, on-theme and on-target. */
    suspend fun drillPlan(request: DrillPlanRequest): DrillPlanModel =
        client.post("$baseUrl/v1/drills") {
            noCache()
            contentType(ContentType.Application.Json)
            setBody(request.copy(language = request.language.ifBlank { language }))
        }.body<DrillPlanModel>().let { plan -> plan.copy(drills = plan.drills.map { it.forAccent(accent, authoredAccent) }) }

    /**
     * A short-lived nonce for Sign in with Google, bound to this device. It goes
     * into the Credential Manager request, so the ID token Google returns can
     * only be used from this device.
     */
    suspend fun googleSignInNonce(): String =
        client.post("$baseUrl/v1/auth/google/nonce") {
            noCache()
        }.successBody<GoogleSignInNonceResponse>().nonce

    /**
     * Signs this device in with the Google account the learner picked. The
     * first time it links the account where it is; on a new phone the account
     * moves here. Answers the `/v1/me` snapshot;
     * 401 is a rejected token and 409 a device linked to another Google account.
     */
    suspend fun signInWithGoogle(idToken: String): AccountState =
        client.post("$baseUrl/v1/auth/google") {
            noCache()
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest(idToken))
        }.successBody()

    /** Sends a batch of usage events, stamped with the device clock now, and answers the HTTP status. */
    suspend fun sendEvents(events: List<ClientEvent>, sentAt: Long = Clock.System.now().toEpochMilliseconds()): Int =
        client.post("$baseUrl/v1/events") {
            noCache()
            contentType(ContentType.Application.Json)
            setBody(ClientEventBatch(events, sentAt))
        }.status.value

    /** Erases every usage event of this device on the server. */
    suspend fun deleteEvents(): Boolean =
        client.delete("$baseUrl/v1/events") { noCache() }.status.isSuccess()

    /** Sends a crash or error trace and answers the HTTP status. */
    suspend fun sendCrashTrace(trace: CrashTrace): Int =
        client.post("$baseUrl/v1/crash-traces") {
            noCache()
            contentType(ContentType.Application.Json)
            setBody(trace)
        }.status.value

    suspend fun stories(limit: Int = 20): List<StoryModel> =
        client.get("$baseUrl/v1/content/STORY") {
            versioned()
            url {
                parameters.append("language", language)
                parameters.append("limit", limit.toString())
            }
        }.body<List<StoryModel>>().map { it.forAccent(accent) }

    /**
     * The lexicon of a theme. A reading's tappable vocabulary is the word bank of
     * its own theme, so nothing is glossed twice: the reader and the lesson show
     * the same IPA, meaning and audio.
     */
    suspend fun words(theme: String? = null, limit: Int = 200): List<WordModel> =
        client.get("$baseUrl/v1/content/WORD") {
            versioned()
            url {
                parameters.append("language", language)
                theme?.let { parameters.append("theme", it) }
                parameters.append("limit", limit.toString())
            }
        }.body<List<WordModel>>().map { it.forAccent(accent, authoredAccent) }

    /**
     * Looks words up by lemma, for the minimal-pair example words in pronunciation
     * feedback. The WORD bank is far larger than one page, so asking for exactly
     * the lemmas wanted is what makes a single word resolvable at all.
     *
     * A server that predates the `lemmas` filter ignores it and answers with a
     * page of words; the result is filtered here by lemma, so a caller always gets
     * back only what it asked for (or less) regardless of the deployment.
     */
    suspend fun wordsByLemmas(lemmas: Collection<String>): List<WordModel> {
        val wanted = lemmas.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyList()
        val fetched: List<WordModel> = client.get("$baseUrl/v1/content/WORD") {
            versioned()
            url {
                parameters.append("language", language)
                parameters.append("lemmas", wanted.joinToString(","))
                parameters.append("limit", wanted.size.coerceIn(1, 200).toString())
            }
        }.body()
        val wantedSet = wanted.toSet()
        return fetched.filter { it.lemma.trim().lowercase() in wantedSet }.map { it.forAccent(accent, authoredAccent) }
    }

    suspend fun exercises(
        theme: String? = null,
        ref: String? = null,
        limit: Int = 50,
    ): List<ExerciseModel> =
        client.get("$baseUrl/v1/content/EXERCISE") {
            versioned()
            url {
                parameters.append("language", language)
                theme?.let { parameters.append("theme", it) }
                ref?.let { parameters.append("ref", it) }
                parameters.append("limit", limit.toString())
            }
        }.body<List<ExerciseModel>>().map { it.forAccent(accent).localized(uiLanguage) }

    /** The study units: one base item plus exercises plus drill targets each. */
    suspend fun lessons(level: String? = null, limit: Int = 200): List<LessonModel> =
        client.get("$baseUrl/v1/content/LESSON") {
            versioned()
            url {
                parameters.append("language", language)
                level?.let { parameters.append("level", it) }
                parameters.append("limit", limit.toString())
            }
        }.body<List<LessonModel>>().map { it.forAccent(accent).localized(uiLanguage) }

    suspend fun dialoguesByIds(ids: List<String>): List<DialogueModel> =
        byIds<DialogueModel>("DIALOGUE", ids) { it.id }.map { it.forAccent(accent, authoredAccent) }

    suspend fun storiesByIds(ids: List<String>): List<StoryModel> =
        byIds<StoryModel>("STORY", ids) { it.id }.map { it.forAccent(accent) }

    suspend fun articlesByIds(ids: List<String>): List<ArticleModel> =
        byIds<ArticleModel>("ARTICLE", ids) { it.id }.map { it.forAccent(accent) }

    suspend fun exercisesByIds(ids: List<String>): List<ExerciseModel> =
        byIds<ExerciseModel>("EXERCISE", ids) { it.id }.map { it.forAccent(accent).localized(uiLanguage) }

    suspend fun wordsByIds(ids: List<String>): List<WordModel> =
        byIds<WordModel>("WORD", ids) { it.id }.map { it.forAccent(accent, authoredAccent) }

    suspend fun drillsByTheme(theme: String, level: String? = null, limit: Int = 8): List<DrillModel> =
        client.get("$baseUrl/v1/content/DRILL") {
            versioned()
            url {
                parameters.append("language", language)
                parameters.append("theme", theme)
                level?.let { parameters.append("level", it) }
                parameters.append("limit", limit.toString())
            }
        }.body<List<DrillModel>>().map { it.forAccent(accent, authoredAccent) }

    /**
     * Fetches items by id. A server that honours `ids` returns exactly those, but
     * an older build may ignore the filter, so ask for the whole kind (every bank
     * stays under the server's 200 cap), filter here and keep the requested order.
     * That makes lesson bases and exercises correct regardless of the deployment.
     */
    private suspend inline fun <reified T> byIds(
        kind: String,
        ids: List<String>,
        idOf: (T) -> String,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val fetched: List<T> = client.get("$baseUrl/v1/content/$kind") {
            versioned()
            url {
                parameters.append("language", language)
                parameters.append("ids", ids.joinToString(","))
                parameters.append("limit", "200")
            }
        }.body()
        if (fetched.isEmpty()) return emptyList()
        val byId = fetched.associateBy(idOf)
        val ordered = ids.mapNotNull { byId[it] }
        return if (ordered.isNotEmpty()) ordered else fetched
    }

    /**
     * Absolute URL of an asset-relative path ("audio/foo.ogg", "images/bar.png")
     * for the current target language, under `content/<lang>/`.
     */
    @Volatile
    private var assetVersion: String = versionStore.load().orEmpty()

    /** The last manifest fetched, for local size estimates and per-asset sizes. */
    @Volatile
    private var lastManifest: ContentManifest? = null

    /** True once a manifest has been fetched, so it is not revalidated needlessly. */
    @Volatile
    private var manifestLoaded: Boolean = false

    val isManifestLoaded: Boolean get() = manifestLoaded

    private val manifestLock = Mutex()

    /** The content version currently in use, empty until the manifest is seen. */
    val contentVersion: String get() = assetVersion

    /**
     * Notified when the served content version changes, so the offline store can
     * drop assets downloaded for an older catalogue. Set once at app startup.
     */
    @Volatile
    var onContentVersionChanged: ((String) -> Unit)? = null

    /**
     * Records the served content version so URLs carry `?v=`, letting a rebuilt
     * catalogue survive any stale CDN/HTTP cache. When the version changes, the
     * entries cached under the old one are dropped instead of lingering.
     */
    private suspend fun updateAssetVersion(version: String) {
        if (version.isBlank() || version == assetVersion) return
        val previous = assetVersion
        assetVersion = version
        versionStore.save(version)
        if (previous.isNotEmpty()) contentCache?.clear()
        onContentVersionChanged?.invoke(version)
    }

    /**
     * Revalidates the manifest and records the served content version, so a cold
     * start that had the old version persisted learns the new one. Cheap: the
     * server answers 304 when nothing changed. Single-flight, so several screens
     * calling it on start share one request; a failure (offline) leaves the
     * persisted version in place.
     */
    suspend fun ensureAssetVersion() {
        manifestLock.withLock { runCatching { manifest() } }
    }

    fun assetUrl(path: String): String {
        if (path.isBlank()) return ""
        val base = baseUrl // first: a server switch resets the version read below
        val suffix = if (assetVersion.isEmpty()) "" else "?v=$assetVersion"
        return "$base/assets/content/$language/$path$suffix"
    }

    /** Absolute URL of a shared asset that is not per-language (model, vocab). */
    fun sharedAssetUrl(path: String): String = "$baseUrl/assets/$path"

    /**
     * The SHA-256 the server publishes next to a shared asset (`<asset>.sha256`),
     * or null when it has none (an older deployment). Lets the download be
     * verified against something other than the Content-Length the same server
     * just sent.
     */
    suspend fun sharedAssetSha256(path: String): String? = runCatching {
        client.get(sharedAssetUrl("$path.sha256")) {
            headers { append(HttpHeaders.CacheControl, "no-cache") }
        }.bodyAsText().trim().takeIf { it.matches(SHA256_HEX) }
    }.getOrNull()

    /** Downloads an asset's bytes, for local images and the offline store. */
    suspend fun assetBytes(path: String): ByteArray =
        client.get(assetUrl(path)) {
            headers { append(HttpHeaders.CacheControl, "no-cache") }
        }.body()

    /**
     * Content length of an asset, used to estimate a download before it starts.
     * Uses GET-without-reading rather than HEAD: the static file route answers
     * HEAD with 404, and the body is discarded at the end of the block.
     */
    suspend fun assetSize(path: String): Long? =
        runCatching {
            client.prepareGet(assetUrl(path)) {
                headers { append(HttpHeaders.CacheControl, "no-cache") }
            }.execute { response ->
                response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            }
        }.getOrNull()

    /**
     * Tags a content request with the current content version, so a new catalog
     * is a new URL and the HTTP cache can never answer with the old one. The
     * server marks versioned content immutable, which is what makes it reusable
     * offline.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.versioned() {
        if (assetVersion.isEmpty()) return
        url { parameters.append("v", assetVersion) }
    }

    /**
     * The decoded body of a 2xx answer, or [ApiStatusException]. The account
     * calls need it: [AccountState] has defaults for every field, so an error
     * body like `{"error": ...}` would otherwise decode into an empty account.
     */
    private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.successBody(): T {
        if (!status.isSuccess()) throw ApiStatusException(status.value)
        return body()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.noCache() {
        headers { append(HttpHeaders.CacheControl, "no-cache") }
    }
}

/** Shared client credential the scoring oracle expects; see [GlossoApi.apiKey]. */
private const val API_KEY_HEADER = "X-Api-Key"

/** The device identity the server keys the user by; see [GlossoApi.deviceIdProvider]. */
private const val DEVICE_ID_HEADER = "X-Device-Id"

/** `off` when the learner turned usage statistics off; see [GlossoApi.analyticsEnabled]. */
private const val ANALYTICS_HEADER = "X-Analytics"

/** A 64-hex-character SHA-256, so a stray 404 page is never taken as a digest. */
private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
