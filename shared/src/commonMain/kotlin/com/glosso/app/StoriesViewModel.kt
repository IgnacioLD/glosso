package com.glosso.app

import com.glosso.app.audio.clipFor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glosso.app.api.GlossoApi
import com.glosso.app.platform.platformInfo
import com.glosso.app.audio.AudioSource
import com.glosso.app.audio.PiecePlayer
import com.glosso.app.audio.PieceState
import com.glosso.app.audio.Player
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.WordModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StoriesState(
    val loading: Boolean = false,
    val stories: List<StoryModel> = emptyList(),
    val exercises: Map<String, List<ExerciseModel>> = emptyMap(),
    val themeLabels: Map<String, String> = emptyMap(),
    val error: String? = null,
    val reading: StoryModel? = null,
    /** The open story's theme vocabulary, by lemma; empty when it has none. */
    val vocabulary: Map<String, WordModel> = emptyMap(),
    /** The word whose IPA, meaning and audio the learner asked to see. */
    val word: WordModel? = null,
)

class StoriesViewModel(
    private val services: AppServices,
    private val api: GlossoApi = services.newApi(),
) : ViewModel() {

    private val platform = platformInfo(services.context)

    private val _state = MutableStateFlow(StoriesState())
    val state: StateFlow<StoriesState> = _state.asStateFlow()

    private val player = Player()
    private val piece = PiecePlayer(player, { VoicePreferences.accent }) { path -> sourceOf(path) }

    /** Which paragraph of the open story is playing, and its status. */
    val pieceState: StateFlow<PieceState> = piece.state

    /** The furthest point of the open story reached, by scroll or by audio, 0..1. */
    private var furthestRead = 0f

    init {
        piece.onUserPlay = { slow -> UsageEvents.audioPlayed("story", slow) }
        piece.onPlaybackError = { UsageEvents.appError("audio_failed", "story") }
        // Listening counts as reading: the paragraph being played marks how far
        // through the story the learner got.
        viewModelScope.launch {
            piece.state.collect { playback ->
                if (_state.value.reading != null && playback.cursor >= 0 && playback.queue.isNotEmpty()) {
                    onReadProgress((playback.cursor + 1).toFloat() / playback.queue.size)
                }
            }
        }
        // Reload on target-language or interface-language change; the first
        // emission is the first load. The UI language matters because the story
        // theme labels come from the server manifest per UI language.
        viewModelScope.launch {
            api.ensureAssetVersion()
            contentReloadSignal(LanguagePreferences.code, UiLanguage.current).collect { load() }
        }
    }

    fun assetUrl(path: String): String = api.assetUrl(path)

    fun openStory(story: StoryModel) {
        furthestRead = 0f
        Analytics.track("story_opened", mapOf("story_id" to story.id, "cefr" to story.level))
        _state.value = _state.value.copy(reading = story, vocabulary = emptyMap(), word = null)
        piece.load(story.paragraphAudio)
        loadExercises(story.id)
        loadVocabulary(story)
    }

    fun closeStory() {
        _state.value.reading?.let { story ->
            Analytics.track("story_closed", mapOf("story_id" to story.id, "read_pct" to readPercent(furthestRead)))
        }
        piece.clear()
        _state.value = _state.value.copy(reading = null, vocabulary = emptyMap(), word = null)
    }

    /** How far the reader has scrolled through the open story, 0..1; only the furthest point is kept. */
    fun onReadProgress(fraction: Float) {
        if (fraction > furthestRead) furthestRead = fraction.coerceAtMost(1f)
    }

    /** The word tapped in the text, or null to hide its detail. */
    fun showWord(word: WordModel?) {
        _state.value = _state.value.copy(word = word)
    }

    /** Plays a vocabulary word's reference, taking the player from the piece. */
    fun playWordAudio(word: WordModel) {
        val path = word.audio.clipFor(VoicePreferences.accent)
        if (path.isNullOrBlank()) return
        player.play(sourceOf(path))
        UsageEvents.audioPlayed("word")
    }

    /** Loads the story's theme vocabulary; a failure just leaves the text plain. */
    private fun loadVocabulary(story: StoryModel) {
        viewModelScope.launch {
            val words = runCatching { api.words(story.theme) }.getOrDefault(emptyList())
            // A story opened while this was in flight wins; never attach the old set.
            if (_state.value.reading?.id != story.id) return@launch
            _state.value = _state.value.copy(vocabulary = StoryVocabulary.index(words))
        }
    }

    /** Big play/pause for the open story. */
    fun toggleParagraphPlayback() = piece.toggle()

    /** Tapping paragraph [index] plays the story from there. */
    fun playParagraphFrom(index: Int) = piece.playFrom(index)

    fun setSlowParagraphs(slow: Boolean) = piece.setSlow(slow)

    private fun sourceOf(path: String): AudioSource = AssetResolver.audio(path, api)

    override fun onCleared() {
        player.stop()
    }

    fun load() {
        piece.clear()
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                error = null,
                reading = null,
                vocabulary = emptyMap(),
                word = null,
                exercises = emptyMap(),
            )
            val labels = runCatching { api.manifest() }.getOrNull()
                ?.themeLabels(platform.uiLanguage) ?: emptyMap()
            runCatching { api.stories(limit = 30) }
                .onSuccess { list ->
                    _state.value = _state.value.copy(loading = false, stories = list, themeLabels = labels)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = networkOrServerError(services.connectivity),
                    )
                    UsageEvents.appError("content_failed", "stories", e)
                }
        }
    }

    /** Loads a story's exercises once and caches them. */
    fun loadExercises(storyId: String) {
        if (_state.value.exercises.containsKey(storyId)) return
        viewModelScope.launch {
            runCatching { api.exercises(ref = storyId) }
                .onSuccess { list ->
                    _state.value = _state.value.copy(exercises = _state.value.exercises + (storyId to list))
                }
        }
    }
}
