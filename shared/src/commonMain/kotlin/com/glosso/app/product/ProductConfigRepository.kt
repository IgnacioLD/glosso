package com.glosso.app.product

import com.glosso.app.GoogleAccountStore
import com.glosso.app.api.AccountState
import com.glosso.app.api.ProductConfig
import com.glosso.app.api.GlossoApi
import com.glosso.app.billing.AccessPolicy
import com.glosso.app.billing.ProductAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlin.concurrent.Volatile

/**
 * The app's single view of `/v1/config` and `/v1/me`.
 *
 * It is fail-open by design. It starts on [ProductConfig.BetaFallback] (the beta
 * is open, nothing is locked, nothing is sold) and only ever moves to what the
 * server actually says. When the server cannot be reached it keeps the last good
 * body it persisted, so a cold, offline start still knows the beta is on and
 * shows the real end date; it never invents a wall or a price.
 *
 * It refreshes once on start and then periodically, and recomputes the derived
 * [access] and [versionNotice] on every change. The server stays the authority:
 * the plan, the flags, the prices and the versions all come from here.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The app's API client, wired by the app at startup (the process-wide `appApi` on Android). */
    lateinit var api: () -> GlossoApi

    @Volatile
    private var prefs: KeyValueStore? = null

    @Volatile
    private var started = false

    @Volatile
    private var refreshing = false

    private val _config = MutableStateFlow(ProductConfig.BetaFallback)
    val config: StateFlow<ProductConfig> = _config.asStateFlow()

    private val _account = MutableStateFlow<AccountState?>(null)
    val account: StateFlow<AccountState?> = _account.asStateFlow()

    private val _access = MutableStateFlow(AccessPolicy.productAccess(ProductConfig.BetaFallback, null))
    val access: StateFlow<ProductAccess> = _access.asStateFlow()

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
        try {
            runCatching { api().productConfig() }.getOrNull()?.let { fetched ->
                _config.value = fetched
                persist(KEY_CONFIG, encodeConfig(fetched))
            }
            runCatching { api().me() }.getOrNull()?.let { fetched ->
                _account.value = fetched
                persist(KEY_ACCOUNT, encodeAccount(fetched))
                GoogleAccountStore.reconcile(fetched.linkedAccounts)
            }
            recompute()
        } finally {
            refreshing = false
        }
    }

    private fun loadCached() {
        prefs?.getString(KEY_CONFIG)?.let { cached ->
            decodeConfig(cached)?.let { _config.value = it }
        }
        prefs?.getString(KEY_ACCOUNT)?.let { cached ->
            decodeAccount(cached)?.let { _account.value = it }
        }
        recompute()
    }

    private fun recompute() {
        _access.value = AccessPolicy.productAccess(_config.value, _account.value)
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
