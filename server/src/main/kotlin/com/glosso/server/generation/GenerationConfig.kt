package com.glosso.server.generation

/**
 * Tunables of the generation queue and worker. Every value comes from the
 * environment; nothing production lives in code. See `deploy/.env.example`.
 */
data class GenerationConfig(
    /** Provider and model the job hash binds to; a change is a new job. */
    val provider: String,
    val model: String,
    val maxAttempts: Int = 3,
    val maxMessagesPerRun: Int = 50,
    val maxClassesPerCycle: Int = 50,
    /** Base retry backoff; the Nth retry waits N * this. */
    val retryBackoffMs: Long = 30_000,
    /** How long the worker blocks for a message when the queue is empty. */
    val workerBlockMs: Long = 1_000,
) {
    companion object {
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            provider: String,
            model: String,
        ): GenerationConfig {
            fun value(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(key: String, default: Int) = value(key)?.toIntOrNull() ?: default
            fun long(key: String, default: Long) = value(key)?.toLongOrNull() ?: default
            return GenerationConfig(
                provider = provider,
                model = model,
                maxAttempts = int("GLOSSO_GENERATION_MAX_ATTEMPTS", 3).coerceAtLeast(1),
                maxMessagesPerRun = int("GLOSSO_GENERATION_MAX_MESSAGES", 50).coerceAtLeast(1),
                maxClassesPerCycle = int("GLOSSO_GENERATION_MAX_CLASSES", 50).coerceAtLeast(1),
                retryBackoffMs = long("GLOSSO_GENERATION_RETRY_BACKOFF_MS", 30_000).coerceAtLeast(0),
                workerBlockMs = long("GLOSSO_GENERATION_WORKER_BLOCK_MS", 1_000).coerceAtLeast(0),
            )
        }
    }
}
