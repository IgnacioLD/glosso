package com.glosso.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glosso.app.api.GlossoApi
import com.glosso.app.platform.platformInfo
import com.glosso.engine.model.ExerciseModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExercisesState(
    val loading: Boolean = false,
    val exercises: List<ExerciseModel> = emptyList(),
    /** Base each exercise answers from, when the content names one. */
    val sources: Map<String, ExerciseSource> = emptyMap(),
    val themeLabels: Map<String, String> = emptyMap(),
    val error: String? = null,
)

class ExercisesViewModel(
    private val services: AppServices,
    private val api: GlossoApi = services.newApi(),
) : ViewModel() {

    private val platform = platformInfo(services.context)

    private val _state = MutableStateFlow(ExercisesState())
    val state: StateFlow<ExercisesState> = _state.asStateFlow()

    init {
        // Reload on target-language or interface-language change; the first
        // emission is the first load. The UI language matters because the theme
        // labels come from the server manifest per UI language.
        viewModelScope.launch {
            contentReloadSignal(LanguagePreferences.code, UiLanguage.current).collect { load() }
        }
    }

    /** Absolute URL of an exercise image ("images/<id>.png"). */
    fun assetUrl(path: String?): String = api.assetUrl(path ?: "")

    /**
     * The base an exercise answers from, or null for a task that stands on its
     * own. The bases are resolved after the exercises land, from the lesson bank
     * and only the stories the exercises reference, so the list paints first.
     */
    fun sourceOf(exercise: ExerciseModel): ExerciseSource? = _state.value.sources[exercise.id]

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val labels = runCatching { api.manifest() }.getOrNull()
                ?.themeLabels(platform.uiLanguage) ?: emptyMap()
            // The exercises are what the screen shows, so they go first: the list
            // paints as soon as they land. Only then are the bases resolved, in
            // parallel, and only for the stories the exercises actually name.
            runCatching { api.exercises(limit = 200) }
                .onSuccess { exercises ->
                    val storyIds = exercises.mapNotNull { it.storyId }.distinct()
                    val (lessons, stories) = coroutineScope {
                        val lessons = async { runCatching { api.lessons(limit = 200) }.getOrDefault(emptyList()) }
                        val stories = async {
                            if (storyIds.isEmpty()) emptyList()
                            else runCatching { api.storiesByIds(storyIds) }.getOrDefault(emptyList())
                        }
                        lessons.await() to stories.await()
                    }
                    _state.value = _state.value.copy(
                        loading = false,
                        exercises = exercises,
                        sources = exerciseSources(exercises, lessons, stories),
                        themeLabels = labels,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = networkOrServerError(services.connectivity),
                    )
                    UsageEvents.appError("content_failed", "exercises", e)
                }
        }
    }
}
