package com.glosso.server.tts

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.time.Duration
import javax.sql.DataSource

/** Assembled TTS layer. Close it to release the HTTP client and any owned pool. */
class TtsRuntime(
    val config: TtsConfig,
    val service: TtsService,
    val processor: TtsJobProcessor,
    private val resources: List<AutoCloseable>,
) : AutoCloseable {
    override fun close() {
        resources.forEach { runCatching { it.close() } }
    }
}

/**
 * Composition root. Reads everything from the environment, builds the provider
 * chain, the object store and the metadata store. Returns null (and logs why)
 * when no provider is configured, so the API keeps running without TTS.
 *
 * The worker should pass the shared control-plane [DataSource] so there is one
 * pool, not two.
 */
object TtsAssembly {

    fun fromEnv(
        env: Map<String, String> = System.getenv(),
        dataSource: DataSource? = null,
        observability: TtsObservability = LoggingTtsObservability(),
        log: (String) -> Unit = {},
    ): TtsRuntime? {
        val config = TtsConfig.fromEnv(env)
        val pricing = TtsPricing(config.pricing)
        val transport = JdkHttpTransport(Duration.ofSeconds(config.timeoutSeconds))

        val chainIds = (listOf(config.provider) + config.fallbackProviders).distinct()
        val providers = chainIds.mapNotNull { id ->
            when (id) {
                CartesiaTtsProvider.ID ->
                    if (config.cartesia.enabled) {
                        CartesiaTtsProvider(config.cartesia, transport, pricing)
                    } else {
                        log("TTS provider 'cartesia' skipped: CARTESIA_API_KEY is not set")
                        null
                    }
                ElevenLabsTtsProvider.ID ->
                    if (config.elevenlabs.enabled) {
                        ElevenLabsTtsProvider(config.elevenlabs, transport, pricing)
                    } else {
                        log("TTS provider 'elevenlabs' skipped: ELEVENLABS_API_KEY is not set")
                        null
                    }
                else -> {
                    log("TTS provider '$id' is not known")
                    null
                }
            }
        }
        if (providers.isEmpty()) {
            transport.close()
            log("TTS disabled: no provider is configured")
            return null
        }

        val router = TtsProviderRouter(
            providers = providers,
            maxRetries = config.maxRetries,
            maxConcurrency = config.maxConcurrency,
            observability = observability,
            voicePalette = config.voicePalette,
        )

        val objectStore: ObjectStore = S3ObjectStore.fromConfig(config.r2)
            ?: config.cacheDir?.let { LocalFileObjectStore(File(it)) }
            ?: run {
                log("TTS object storage not configured (GLOSSO_R2_* or GLOSSO_TTS_CACHE_DIR); using in-memory (not durable)")
                InMemoryObjectStore()
            }

        val ownedDataSource: DataSource?
        val assetStore: TtsAssetStore
        if (dataSource != null) {
            assetStore = JdbcTtsAssetStore(dataSource).also { it.ensureSchema() }
            ownedDataSource = null
        } else {
            val url = env["GLOSSO_DB_URL"]?.takeIf { it.isNotBlank() }
            if (url != null) {
                val pool = hikari(url, env)
                assetStore = JdbcTtsAssetStore(pool).also { it.ensureSchema() }
                ownedDataSource = pool
            } else {
                log("TTS metadata store not configured (GLOSSO_DB_URL); using in-memory (not durable)")
                assetStore = InMemoryTtsAssetStore()
                ownedDataSource = null
            }
        }

        val service = TtsService(
            router = router,
            objectStore = objectStore,
            assetStore = assetStore,
            validator = AudioValidator(),
            pricing = pricing,
            config = config,
            observability = observability,
        )
        log("TTS ready: providers=${providers.joinToString { it.id }}, storage=${objectStore.id}")

        val resources = buildList<AutoCloseable> {
            add(transport)
            add(objectStore)
            add(assetStore)
            ownedDataSource?.let { add(it) }
        }
        return TtsRuntime(config, service, TtsJobProcessor(service), resources)
    }

    private fun hikari(url: String, env: Map<String, String>): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = env["GLOSSO_DB_USER"] ?: "postgres"
            password = env["GLOSSO_DB_PASSWORD"] ?: ""
            maximumPoolSize = 3
            minimumIdle = 1
            connectionTimeout = 5_000
            poolName = "glosso-tts"
        }
        return HikariDataSource(config)
    }
}
