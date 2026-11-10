package com.glosso.server.tts

/**
 * The one abstraction the product depends on. Adding OpenAI, Google, Azure or a
 * local model is a new implementation of this interface plus a line in the
 * provider factory; no change to the content generator, the pool or the worker.
 */
interface TtsProvider {

    /** Stable lower-case id, e.g. `cartesia`. Part of the cache fingerprint. */
    val id: String

    /** Model used when the request does not pin one. */
    fun defaultModel(): String

    /** Voice used when the request does not pin one. */
    fun defaultVoice(): String

    /**
     * Languages this provider voices. Empty means "no local allow-list", i.e.
     * whatever the vendor accepts. Used to fail fast on a permanent content
     * error (unsupported language) instead of sending a doomed request.
     */
    val supportedLanguages: Set<String> get() = emptySet()

    /**
     * Synthesizes [request] at [speedFactor] (1.0 normal, lower is slower). Only
     * text travels to the provider; never learner audio.
     *
     * @throws TtsException with a [TtsFailureKind] that says whether the failure
     * is transient (retry/fallback allowed) or permanent.
     */
    fun synthesize(request: TtsRequest, speedFactor: Double): TtsResult
}
