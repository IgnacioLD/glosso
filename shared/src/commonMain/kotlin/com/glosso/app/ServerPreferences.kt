package com.glosso.app

import com.glosso.app.api.ApiCache
import com.glosso.app.api.GlossoApi
import com.glosso.app.api.defaultHttpEngine
import com.glosso.app.download.Downloader
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.app.product.ProductConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Glosso server the app talks to. The build's address ([defaultBase]) is the
 * default; the learner can point the app at a self-hosted server in Settings.
 * Every request reads [base] at call time (see `GlossoApi`), so all traffic
 * follows the choice. Change it only through [ServerSwitch], which drops what
 * belonged to the previous server first.
 */
object ServerPreferences {
    private const val FILE = "glosso_server"
    private const val KEY = "base_url"

    private var prefs: KeyValueStore? = null

    /** The build's server (`glossoApiBase` on Android, `GLOSSO_API_BASE` on iOS). */
    var defaultBase: String = ""
        private set

    private val _base = MutableStateFlow("")

    /** The server in use: the learner's choice, or [defaultBase]. */
    val base: StateFlow<String> = _base.asStateFlow()

    val isCustom: Boolean get() = _base.value != defaultBase

    fun init(context: PlatformContext, default: String) = init(keyValueStore(context, FILE), default)

    fun init(store: KeyValueStore, default: String) {
        prefs = store
        defaultBase = default.trimEnd('/')
        _base.value = store.getString(KEY)?.takeIf { it.isNotBlank() } ?: defaultBase
    }

    /** Uses [url] (already normalized by [checkServerUrl]), or the default when null. */
    internal fun set(url: String?) {
        val chosen = url?.takeIf { it != defaultBase }
        prefs?.edit(commit = true) { if (chosen == null) remove(KEY) else putString(KEY, chosen) }
        _base.value = chosen ?: defaultBase
    }
}

/** What the learner typed, checked: a normalized base URL or why it is refused. */
sealed interface ServerUrlCheck {
    data class Valid(val url: String) : ServerUrlCheck
    data object Invalid : ServerUrlCheck

    /** Plain http, which only a debug build accepts and only for a local server. */
    data object HttpsRequired : ServerUrlCheck
}

private val SERVER_URL = Regex(
    """^(https?)://([a-z0-9.-]+|\[[0-9a-f:.]+])(?::(\d{1,5}))?(/[^?#\s]*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Checks and normalizes a server address: a bare host means https, scheme and
 * host are lowercased, trailing slashes go, and no query, fragment or user info
 * is allowed. Plain http is refused unless [allowCleartext] (debug builds) and
 * the host is local ([isLocalHost]): release builds deny cleartext on Android.
 */
fun checkServerUrl(input: String, allowCleartext: Boolean): ServerUrlCheck {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ServerUrlCheck.Invalid
    val raw = if ("://" in trimmed) trimmed else "https://$trimmed"
    val match = SERVER_URL.matchEntire(raw) ?: return ServerUrlCheck.Invalid
    val (schemeRaw, hostRaw, port, pathRaw) = match.destructured
    val scheme = schemeRaw.lowercase()
    val host = hostRaw.lowercase()
    if (host.startsWith('.') || host.endsWith('.') || ".." in host) return ServerUrlCheck.Invalid
    if (port.isNotEmpty() && port.toInt() !in 1..65535) return ServerUrlCheck.Invalid
    if (scheme == "http" && !(allowCleartext && isLocalHost(host))) return ServerUrlCheck.HttpsRequired
    val portPart = if (port.isEmpty()) "" else ":$port"
    return ServerUrlCheck.Valid("$scheme://$host$portPart${pathRaw.trimEnd('/')}")
}

/** Loopback, the emulator's host alias (10.0.2.2) and the private IPv4 ranges. */
internal fun isLocalHost(host: String): Boolean {
    if (host == "localhost" || host == "[::1]") return true
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

enum class ServerProbe { OK, UNREACHABLE, NOT_GLOSSO }

/**
 * Whether [base] is a Glosso server: `/health` answers `{"status":"ok", ...,
 * "assets": ...}` and `/v1/config` answers a JSON object (both public, no
 * database needed). A fresh client sends no device id, so an address the learner
 * mistyped never learns who they are.
 */
suspend fun probeServer(base: String, engine: HttpClientEngine? = null): ServerProbe {
    val client = HttpClient(engine ?: defaultHttpEngine()) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }
    return try {
        val health = client.get("$base/health")
        val config = if (health.status.isSuccess()) client.get("$base/v1/config") else null
        val glosso = config != null && config.status.isSuccess() &&
            jsonObjectOrNull(health.bodyAsText())?.let {
                it["status"]?.jsonPrimitive?.content == "ok" && "assets" in it
            } == true &&
            jsonObjectOrNull(config.bodyAsText()) != null
        if (glosso) ServerProbe.OK else ServerProbe.NOT_GLOSSO
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerProbe.UNREACHABLE
    } finally {
        client.close()
    }
}

private fun jsonObjectOrNull(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

/**
 * Moves the app to another server. Everything that belonged to the old one is
 * dropped first, so nothing from server A is used against server B: queued
 * downloads, the HTTP cache and content version, downloaded lesson packs, the
 * cached product config and account, the linked Google account and unsent usage
 * events. The learner's own progress (lessons, sounds, streak) is local and kept.
 * The scoring model is kept only if its digest is one the new server accepts.
 */
object ServerSwitch {
    /** Switches to [url] (normalized), or back to the default when null. */
    suspend fun apply(
        url: String?,
        downloader: Downloader,
        onDevice: OnDeviceScoring,
        api: GlossoApi = appApi,
    ) = withContext(NonCancellable) {
        // NonCancellable: leaving the screen mid-switch must not leave it half done.
        val target = url ?: ServerPreferences.defaultBase
        if (target == ServerPreferences.base.value) return@withContext
        downloader.state.value.active.forEach { downloader.cancel(it.id) }
        ApiCache.forget()
        OfflineStore.clear()
        OfflineStore.setContentVersion("")
        ProductConfigRepository.forget()
        GoogleAccountStore.forget()
        Analytics.dropPending()
        // Last, so every client resets against an already empty content version.
        ServerPreferences.set(url)
        onDevice.onServerChanged()
        ProductConfigRepository.refresh()
        api.ensureAssetVersion()
    }
}
