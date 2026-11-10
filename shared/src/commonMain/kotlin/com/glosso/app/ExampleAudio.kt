package com.glosso.app

import com.glosso.app.audio.clipFor
import com.glosso.app.audio.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plays the reference audio of the minimal-pair example words shown next to a
 * diagnosed sound ("ship", "sheep"). Those words are plain strings from the
 * engine's curated error rules, so the content catalog is looked up by lemma and
 * the result is cached: every pronunciation drill in the app resolves the same
 * words, and the WORD bank is far larger than one page, which is why the lookup
 * is a server-side filter rather than a paged fetch.
 *
 * One shared [Player] owns the sound, so tapping a second word stops the first.
 * Nothing here scores or invents anything: a word without a catalog clip simply
 * is not offered as tappable.
 */
object ExampleAudio {

    private val api get() = appApi
    private val player = Player()

    /**
     * `<accent>|<lemma>` -> asset-relative audio path. Keyed by accent too, so a
     * learner who changes accent gets that accent fetched instead of the cached
     * file of the previous one.
     */
    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths: StateFlow<Map<String, String>> = _paths.asStateFlow()

    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    private val loadLock = Mutex()

    init {
        player.onPlayingChanged = { on -> if (!on) _playing.value = null }
    }

    /** The cache key for [word] under the current accent; shared with the UI. */
    fun cacheKey(word: String): String = key(lemma(word))

    /**
     * Fetches and caches the audio of any of [words] not resolved yet. Safe to
     * call from every card that shows examples; already-known words are skipped
     * and concurrent callers share one request.
     */
    suspend fun resolve(words: Collection<String>) {
        val lemmas = words.map { lemma(it) }.filter { it.isNotBlank() }.distinct()
        if (lemmas.isEmpty()) return
        if (lemmas.all { key(it) in _paths.value }) return
        loadLock.withLock {
            val missing = lemmas.filter { key(it) !in _paths.value }
            if (missing.isEmpty()) return
            // A server that predates the lemma filter ignores it and answers with
            // a page of words; matching by lemma keeps the result correct anyway.
            val found = runCatching { api.wordsByLemmas(missing) }.getOrDefault(emptyList())
            val wanted = missing.toSet()
            val resolved = found.mapNotNull { word ->
                val lemma = lemma(word.lemma)
                if (lemma !in wanted) return@mapNotNull null
                val path = word.audio.clipFor(VoicePreferences.accent) ?: return@mapNotNull null
                key(lemma) to path
            }.toMap()
            if (resolved.isNotEmpty()) {
                _paths.value = _paths.value + resolved
            }
        }
    }

    fun play(word: String) {
        val cacheKey = key(lemma(word))
        val path = _paths.value[cacheKey] ?: return
        _playing.value = cacheKey
        player.play(AssetResolver.audio(path, api))
        UsageEvents.audioPlayed("example")
    }

    fun stop() {
        player.stop()
        _playing.value = null
    }

    /** The cache key: the current accent plus the lemma, so an accent change refetches. */
    private fun key(lemma: String): String = "${VoicePreferences.accent}|$lemma"

    private fun lemma(word: String): String = word.trim().lowercase()
}
