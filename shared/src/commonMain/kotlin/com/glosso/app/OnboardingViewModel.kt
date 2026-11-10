package com.glosso.app

import com.glosso.app.audio.playbackFor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glosso.app.api.GlossoApi
import com.glosso.engine.model.DrillModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How many reviewed phrases the seed step asks the learner to say. */
const val ONBOARDING_SEED_PHRASES = OnboardingSeed.PHRASES

data class SeedState(
    val loading: Boolean = true,
    val error: String? = null,
    val drills: List<DrillModel> = emptyList(),
    val index: Int = 0,
    /** The score of the previous take on the current phrase, or null on the first. */
    val previousScore: Int? = null,
) {
    val drill: DrillModel? get() = drills.getOrNull(index)

    /**
     * True once there is nothing left to say: the last phrase was passed, or the
     * bank legitimately came back empty. An empty bank must not leave the primary
     * action stuck on "Next phrase" with no phrase to show; it reads as done.
     */
    val finished: Boolean get() = !loading && error == null && index >= drills.size
}

/**
 * Drives the onboarding seed: a few phrases a new learner says, each training a
 * different sound, scored through the same engine as practice, so the phonetic
 * profile starts with real evidence instead of an empty screen. Nothing is
 * invented: the phrases are reviewed drills from the content bank, picked by
 * [OnboardingSeed] for coverage and heard through their shipped reference audio,
 * and a failed or skipped recording simply leaves the profile honest.
 */
class OnboardingViewModel(private val services: AppServices) : ViewModel() {

    private val api = services.newApi()
    private val profiles = ProfileStore.get(services.context)
    private val debugTools = services.config.debug
    private val engine = PracticeEngine(
        api,
        viewModelScope,
        services.context,
        profiles,
        services.onDevice,
        exerciseKind = "onboarding_seed",
        screen = "onboarding",
        debugTools = debugTools,
    )

    /** The shared pronunciation loop (recording, scoring, take, voice). */
    val attempt: StateFlow<AttemptState> = engine.state

    /** Live microphone level for the recording waveform, 0..1. */
    val level: StateFlow<Float> = engine.level

    /** True while reference audio or a take is playing. */
    val playing: StateFlow<Boolean> = engine.playing

    private val _seed = MutableStateFlow(SeedState())
    val seed: StateFlow<SeedState> = _seed.asStateFlow()

    init {
        engine.onScored = { feedback ->
            val previous = AttemptHistory.recordTake(_seed.value.drill?.id, feedback.score)
            ProgressStore.recordDrill(
                feedback.score,
                AchievementCatalog.takeSignals(profiles.current, profiles.history.value),
            )
            _seed.value = _seed.value.copy(previousScore = previous)
        }
        load()
        // The first page lets the learner pick the accent, and the phrases are
        // worded per accent: fetch them again in the one just chosen.
        viewModelScope.launch {
            VoicePreferences.accentFlow.drop(1).collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            _seed.value = _seed.value.copy(loading = true, error = null)
            // The bank is paged in row order, so the first few drills repeat the
            // same sounds. Fetch a wide page and pick one phrase per trained
            // sound, so the profile starts with breadth instead of two sounds.
            runCatching { api.drills(limit = OnboardingSeed.POOL) }
                .onSuccess { pool ->
                    _seed.value = SeedState(
                        loading = false,
                        drills = OnboardingSeed.select(pool, ONBOARDING_SEED_PHRASES),
                    )
                }
                .onFailure {
                    _seed.value = SeedState(
                        loading = false,
                        error = networkOrServerError(services.connectivity),
                    )
                }
        }
    }

    fun play(slow: Boolean = false) {
        val drill = _seed.value.drill ?: return
        val clip = drill.audio.playbackFor(VoicePreferences.accent, slow) ?: return
        engine.play(AssetResolver.audio(clip.path, api), clip.rate)
        UsageEvents.audioPlayed("drill", slow)
    }

    fun playTake() = engine.playTake()

    fun toggleRecord() {
        _seed.value.drill?.let { engine.toggle(it.text, it.ipa, it.ipaVariants, referenceAudioPath(it.audio), it.accentIpa) }
    }

    /** Moves to the next phrase, or past the last one, clearing the take. */
    fun next() {
        engine.clearResult()
        _seed.value = _seed.value.copy(index = _seed.value.index + 1, previousScore = null)
    }

    /**
     * Debug-only: scores a take of the current phrase with its target sound
     * swapped for the contrast it is confused with, through the real engine, so
     * the seed and its effect on the profile can be exercised on an emulator
     * with no microphone. Inert in release builds.
     */
    fun debugInjectTake() {
        if (!debugTools) return
        val drill = _seed.value.drill ?: return
        val wrong = drill.contrast?.takeIf { it.isNotBlank() } ?: "s"
        val actual = if (drill.target.isNotBlank() && drill.ipa.contains(drill.target)) {
            drill.ipa.replaceFirst(drill.target, wrong)
        } else {
            drill.ipa
        }
        engine.debugInjectTake(drill.text, drill.ipa, actual)
    }

    override fun onCleared() {
        engine.release()
    }
}
