package com.glosso.app.product

import com.glosso.app.GoogleAccountStore
import com.glosso.app.ServerPreferences
import com.glosso.app.api.AccountState
import com.glosso.app.api.GlossoApi
import com.glosso.app.api.ProductConfig
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.ioDispatcher
import com.glosso.app.platform.keyValueStore
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The app's single view of `/v1/config` and `/v1/me`.
 *
 * It starts on an empty [ProductConfig] and only ever moves to what the server
 * actually says. When the server cannot be reached it keeps the last good body
 * it persisted. It refreshes once on start and then periodically. The server
 * stays the authority on the flags and the version policy.
 */
object ProductConfigRepository {

    private const val FILE = "glosso_product"
    private const val KEY_CONFIG = "config"
    private const val KEY_ACCOUNT = "account"

    /** Long enough to be cheap, short enough that a config change lands the same day. */
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** The app's API client, wired by the app at startup (the process-wide `appApi` on Android). */
    lateinit var api: () -> GlossoApi

    @Volatile
    private var prefs: KeyValueStore? = null

    @Volatile
    private var started = false

    @Volatile
    private var refreshing = false

    private val _config = MutableStateFlow(ProductConfig())
    val config: StateFlow<ProductConfig> = _config.asStateFlow()

    private val _account = MutableStateFlow<AccountState?>(null)
    val account: StateFlow<AccountState?> = _account.asStateFlow()

    /**
     * Reads the cached bodies (if any) and starts the refresh loop. Safe to call
     * from every entry point: only the first call wins.
     */
    fun init(context: PlatformContext) {
        if (started) return
        started = true
        prefs = keyValueStore(context, FILE)
        loadCached()
        scope.launch {
            refresh()
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                refresh()
            }
        }
    }

    /**
     * Fetches `/v1/config` and `/v1/me` once. Config and account are handled
     * independently, so a server that answers one and not the other still
     * improves the state. On any failure the previous values are kept.
     */
    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        // An answer from a server the learner just switched away from is dropped.
        val server = ServerPreferences.base.value
        try {
            runCatching { api().productConfig() }.getOrNull()?.takeIf { server == ServerPreferences.base.value }?.let { fetched ->
                _config.value = fetched
                persist(KEY_CONFIG, encodeConfig(fetched))
            }
            runCatching { api().me() }.getOrNull()?.takeIf { server == ServerPreferences.base.value }?.let { fetched ->
                _account.value = fetched
                persist(KEY_ACCOUNT, encodeAccount(fetched))
                GoogleAccountStore.reconcile(fetched.linkedAccounts)
            }
        } finally {
            refreshing = false
        }
    }

    /** Forgets the previous server's config and account ([ServerSwitch]); a refresh follows. */
    fun forget() {
        _config.value = ProductConfig()
        _account.value = null
        prefs?.edit(commit = true) {
            remove(KEY_CONFIG)
            remove(KEY_ACCOUNT)
        }
        refreshing = false
    }

    private fun loadCached() {
        prefs?.getString(KEY_CONFIG)?.let { cached ->
            decodeConfig(cached)?.let { _config.value = it }
        }
        prefs?.getString(KEY_ACCOUNT)?.let { cached ->
            decodeAccount(cached)?.let { _account.value = it }
        }
    }

    private fun persist(key: String, value: String) {
        prefs?.edit { putString(key, value) }
    }

    fun decodeConfig(raw: String): ProductConfig? =
        runCatching { json.decodeFromString<ProductConfig>(raw) }.getOrNull()

    fun decodeAccount(raw: String): AccountState? =
        runCatching { json.decodeFromString<AccountState>(raw) }.getOrNull()

    fun encodeConfig(config: ProductConfig): String = json.encodeToString(config)

    fun encodeAccount(account: AccountState): String = json.encodeToString(account)
}
