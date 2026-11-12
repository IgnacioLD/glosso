package com.glosso.app

import com.glosso.app.audio.playbackFor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glosso.app.api.DrillPlanRequest
import com.glosso.engine.drills.DrillPlanner
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.PhonemeErrorModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.phonetics.PhoneticLoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the practice list came from, so the UI can be honest about it. */
enum class PracticeSource {
    /** Drills picked from the learner's weak sounds. */
    PROFILE,

    /** No profile yet: the standard bank. */
    GENERAL,

    /** A profile exists but the planner had no usable drill for it. */
    NO_PLAN,
}

/**
 * A sound the learner is working on right now, with the profile's running
 * accuracy for it after each attempt, so the screen can show the trend
 * (33 -> 60 -> 71) instead of a single number.
 */
data class FocusPractice(
    val phoneme: String,
    val drillId: String,
    val scores: List<Int> = emptyList(),
) {
    val from: Int? get() = scores.firstOrNull()
    val to: Int? get() = scores.lastOrNull()
    val improved: Boolean get() = scores.size >= 2 && scores.last() > scores.first()
}

/** The free practice list: which drills, which one is up. The spoken attempt
 *  itself lives in PracticeEngine so it matches the lesson player. */
data class PracticeState(
    val loading: Boolean = false,
    val drills: List<DrillModel> = emptyList(),
    val index: Int = 0,
    val error: String? = null,
    val source: PracticeSource = PracticeSource.GENERAL,
    /** The error sound being trained and how it is trending, if any. */
    val focus: FocusPractice? = null,
    /** True when the last "practise this sound" tap had no drill to offer. */
    val drillUnavailable: Boolean = false,
    /** The score of the previous take on the current drill, or null on the first. */
    val previousScore: Int? = null,
) {
    val drill: DrillModel? get() = drills.getOrNull(index)
}

/**
 * Picks the practice list. With a profile it asks the server to plan from the
 * weakest sounds; a plan that is empty or fails falls back to the general bank,
 * and [PracticeSource.NO_PLAN] records that the profile could not be used so the
 * UI does not claim a personalization that did not happen.
 */
suspend fun planPractice(
    profile: PhoneticProfileModel?,
    plan: suspend (PhoneticProfileModel) -> List<DrillModel>,
    fallback: suspend () -> List<DrillModel>,
): Pair<List<DrillModel>, PracticeSource> {
    if (profile == null) return fallback() to PracticeSource.GENERAL
    val planned = runCatching { plan(profile) }.getOrDefault(emptyList())
    return if (planned.isNotEmpty()) {
        planned to PracticeSource.PROFILE
    } else {
        fallback() to PracticeSource.NO_PLAN
    }
}

class PracticeViewModel(private val services: AppServices) : ViewModel() {

    private val api = appApi
    private val profiles = ProfileStore.get(services.context)
    private val config = services.config
    private val engine = PracticeEngine(
        api,
        viewModelScope,
        services.context,
        profiles,
        services.onDevice,
        exerciseKind = "practice",
        debugTools = config.debug,
    )

    /** The shared pronunciation loop (recording, scoring, take, voice). */
    val attempt: StateFlow<AttemptState> = engine.state

    /** Live microphone level for the recording waveform, 0..1. */
    val level: StateFlow<Float> = engine.level

    /** True while reference audio or a take is playing. */
    val playing: StateFlow<Boolean> = engine.playing

    private val _state = MutableStateFlow(PracticeState())
    val state: StateFlow<PracticeState> = _state.asStateFlow()

    init {
        engine.onScored = { feedback ->
            val previous = AttemptHistory.recordTake(_state.value.drill?.id, feedback.score)
            ProgressStore.recordDrill(
                feedback.score,
                AchievementCatalog.takeSignals(profiles.current, profiles.history.value),
            )
            _state.value = _state.value.copy(drillUnavailable = false, previousScore = previous)
            trackFocus(feedback.profile)
        }
        // Reload whenever the target language changes (collect emits the current
        // one immediately, so this is also the first load).
        viewModelScope.launch {
            api.ensureAssetVersion()
            LanguagePreferences.code.collect { code ->
                profiles.setLanguage(code)
                load()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            profiles.await()
            val profile = profiles.current?.takeIf { it.stats.isNotEmpty() }
            runCatching {
                planPractice(
                    profile = profile,
                    plan = { active ->
                        api.drillPlan(DrillPlanRequest(profile = active, limit = PRACTICE_SIZE)).drills
                    },
                    fallback = { api.drills(limit = PRACTICE_SIZE) },
                )
            }
                .onSuccess { (drills, source) ->
                    _state.value = _state.value.copy(
                        loading = false,
                        drills = drills,
                        index = 0,
                        source = source,
                        focus = null,
                        drillUnavailable = false,
                        previousScore = null,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = networkOrServerError(services.connectivity),
                    )
                    UsageEvents.appError("content_failed", "practice", e)
                }
        }
    }

    fun play(slow: Boolean = false) {
        val drill = _state.value.drill ?: return
        val clip = drill.audio.playbackFor(VoicePreferences.accent, slow) ?: return
        engine.play(AssetResolver.audio(clip.path, api), clip.rate)
        UsageEvents.audioPlayed("drill", slow)
    }

    fun playTake() = engine.playTake()

    fun toggleRecord() {
        _state.value.drill?.let { engine.toggle(it.text, it.ipa, it.ipaVariants, referenceAudioPath(it.audio), it.accentIpa) }
    }

    /** Downloads the on-device scoring pack offered after an offline score failed. */
    fun downloadModel() = engine.downloadOnDeviceModel()

    fun next() {
        val s = _state.value
        if (s.drills.isEmpty()) return
        _state.value = s.copy(
            index = (s.index + 1) % s.drills.size,
            focus = null,
            drillUnavailable = false,
            previousScore = null,
        )
        engine.clearResult()
    }

    /**
     * The loop's one tap: after an error is diagnosed, bring the reviewed drill
     * for that exact sound into the same screen, with its baseline, so the learner
     * records again without going to look for it.
     */
    fun practiceError(error: PhonemeErrorModel) {
        viewModelScope.launch {
            val currentId = _state.value.drill?.id
            // Prefer a different drill from the reviewed bank, so the learner
            // generalises the sound instead of replaying the same sentence.
            val bank = runCatching { api.drills(limit = DRILL_BANK) }.getOrDefault(emptyList())
            val pool = (bank + _state.value.drills).filter { it.id != currentId }
            val drill = DrillPlanner.drillForError(error, pool)
                ?: DrillPlanner.drillForError(error, _state.value.drills)
                ?: run {
                    // No drill trains this sound right now (offline bank or a sound
                    // the bank does not cover). Say so instead of a dead tap.
                    _state.value = _state.value.copy(drillUnavailable = true)
                    return@launch
                }
            val baseline = PhoneticLoop.profileScore(engine.profile, error.phoneme)
            val drills = _state.value.drills.toMutableList()
            if (drills.isEmpty()) {
                drills += drill
                _state.value = _state.value.copy(
                    drills = drills,
                    index = 0,
                    focus = FocusPractice(error.phoneme, drill.id, listOfNotNull(baseline)),
                    previousScore = null,
                )
            } else {
                val index = _state.value.index.coerceIn(0, drills.lastIndex)
                drills[index] = drill
                _state.value = _state.value.copy(
                    drills = drills,
                    index = index,
                    focus = FocusPractice(error.phoneme, drill.id, listOfNotNull(baseline)),
                    previousScore = null,
                )
            }
            engine.clearResult()
        }
    }

    /** Appends the focused sound's running accuracy after each attempt. */
    private fun trackFocus(profile: PhoneticProfileModel?) {
        val focus = _state.value.focus ?: return
        if (_state.value.drill?.id != focus.drillId) return
        val now = PhoneticLoop.profileScore(profile, focus.phoneme) ?: return
        _state.value = _state.value.copy(
            focus = focus.copy(scores = (focus.scores + now).takeLast(6)),
        )
    }

    /**
     * Debug-only fixture for emulators with no microphone: injects a scored take
     * through the real cloud engine. "bad" sets a TH-said-as-S take; "good" reads
     * the current drill perfectly, to show the sound recover. Inert in release.
     */
    fun debugInjectFixture(bad: Boolean) {
        if (!config.debug) return
        if (bad) {
            val take = DebugTakes.badTh
            val drill = DrillModel(
                id = "debug.think",
                target = take.target,
                contrast = "s",
                ruleId = "en.th.stopping",
                focus = take.focus,
                text = take.text,
                ipa = take.ipa,
            )
            val drills = _state.value.drills.toMutableList()
            if (drills.isEmpty()) drills += drill else
                drills[_state.value.index.coerceIn(0, drills.lastIndex)] = drill
            _state.value = _state.value.copy(drills = drills, focus = null)
            engine.debugInjectTake(take.text, take.ipa, take.actual)
        } else {
            val drill = _state.value.drill ?: return
            engine.debugInjectTake(drill.text, drill.ipa, drill.ipa)
        }
    }

    /**
     * Debug-only, with the prosody bonus on: scores another native voice of the
     * current drill as the take, through the on-device model, so the "rhythm and
     * intonation" section can be seen with real audio on an emulator. Inert in
     * release builds.
     */
    fun debugNativeTake() {
        if (!config.debug || !config.prosodyBonus) return
        val drill = _state.value.drill ?: return
        val reference = referenceAudioPath(drill.audio)
        val other = drill.audio.entries.firstOrNull { ':' !in it.key && it.value != reference }?.value ?: return
        viewModelScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { AssetResolver.localBytes(other) } ?: api.assetBytes(other)
                services.onDevice.decodeToWav16k(bytes)
            }.getOrNull()?.let { wav ->
                engine.debugScoreWav(wav, drill.text, drill.ipa, drill.ipaVariants, reference, drill.accentIpa)
            }
        }
    }

    override fun onCleared() {
        engine.release()
    }

    private companion object {
        const val PRACTICE_SIZE = 10
        const val DRILL_BANK = 100
    }
}

/** Canned takes for the debug-only loop fixture (see [PracticeViewModel.debugInjectFixture]). */
object DebugTakes {
    data class NewTake(
        val text: String,
        val ipa: String,
        val actual: String,
        val target: String,
        val focus: List<String>,
    )

    /** "I think it is the third thing", with both TH sounds mispronounced. */
    val badTh = NewTake(
        text = "I think it is the third thing",
        ipa = "aɪ θɪŋk ɪt ɪz ðə θɜːrd θɪŋ",
        actual = "aɪ sɪŋk ɪt ɪz ðə tɜːrd θɪŋ",
        target = "θ",
        focus = listOf("think", "sink"),
    )
}
