package com.glosso.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glosso.app.api.DrillPlanRequest
import com.glosso.app.platform.currentTimeMillis
import com.glosso.app.platform.keyValueStore
import com.glosso.app.platform.platformInfo
import com.glosso.app.audio.AudioSource
import com.glosso.app.audio.PiecePlayer
import com.glosso.app.audio.PieceState
import com.glosso.app.audio.accentOf
import com.glosso.app.audio.clipFor
import com.glosso.app.audio.playbackFor
import com.glosso.app.resources.*
import com.glosso.engine.lesson.RecallCard
import com.glosso.engine.lesson.RecallVerdict
import com.glosso.engine.lesson.SpeakPlanner
import com.glosso.engine.model.ArticleModel
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.WordModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * The lesson flow: say back what is due from earlier lessons, learn how the
 * situation works, meet the base, prove understanding, say it from memory, then
 * train the sounds. [REVIEW] exists only when phrases are due, [CONTEXT] only
 * for a lesson with culture notes and [SPEAK] only for a lesson whose dialogue
 * has lines the learner can be prompted for; see [lessonSteps].
 *
 * The order of the entries is the order of the flow; saved progress stores the
 * name, so an entry can be added but never renamed.
 */
enum class LessonStep { REVIEW, CONTEXT, BASE, EXERCISES, SPEAK, DRILLS, SUMMARY }

/** True for the steps where a phrase is said from memory. */
val LessonStep.fromMemory: Boolean get() = this == LessonStep.REVIEW || this == LessonStep.SPEAK

/** The steps a lesson really has, in order. */
fun lessonSteps(hasSpeak: Boolean, hasReview: Boolean = false, hasContext: Boolean = false): List<LessonStep> =
    LessonStep.entries.filter { step ->
        when (step) {
            LessonStep.REVIEW -> hasReview
            LessonStep.CONTEXT -> hasContext
            LessonStep.SPEAK -> hasSpeak
            else -> true
        }
    }

/**
 * The steps of a lesson seen from the list, before its content is loaded: a
 * dialogue lesson is assumed to have its speaking step.
 */
fun lessonSteps(lesson: LessonModel): List<LessonStep> =
    lessonSteps(hasSpeak = lesson.dialogueId != null, hasContext = lesson.notes.isNotEmpty())

data class LessonState(
    val loading: Boolean = false,
    val error: String? = null,
    val lessons: List<LessonModel> = emptyList(),
    val level: String = "A1",
    /** Levels the content publishes, in order, with "ALL" last when there are several. From the manifest. */
    val levels: List<String> = emptyList(),
    /** Theme display names, straight from the manifest. */
    val themeLabels: Map<String, String> = emptyMap(),
    /** The teaching order of the theme blocks, straight from the manifest. */
    val themeOrder: List<String> = emptyList(),
    val active: LessonModel? = null,
    val step: LessonStep = LessonStep.BASE,
    /** Lesson and step to offer under "Continue", restored across launches. */
    val resumeLessonId: String? = null,
    val resumeStep: LessonStep = LessonStep.BASE,
    val dialogue: DialogueModel? = null,
    val story: StoryModel? = null,
    val article: ArticleModel? = null,
    val words: List<WordModel> = emptyList(),
    val exercises: List<ExerciseModel> = emptyList(),
    val drills: List<DrillModel> = emptyList(),
    val drillIndex: Int = 0,
    /** The dialogue lines the learner says from memory in [LessonStep.SPEAK]. */
    val speakCards: List<RecallCard> = emptyList(),
    /** Phrases of earlier lessons that are due, asked in [LessonStep.REVIEW] before the base. */
    val reviewCards: List<RecallCard> = emptyList(),
    /** How many phrases of this lesson were put on the review ladder. */
    val scheduledForReview: Int = 0,
    val speakIndex: Int = 0,
    /** True once the phrase of the current line is on screen (after a take, or on request). */
    val speakRevealed: Boolean = false,
    /** What the last take of the current line was, or null before one. */
    val speakVerdict: RecallVerdict? = null,
    /** True once the current phrase has been put on the review ladder in this visit. */
    val speakRecorded: Boolean = false,
    /**
     * True while the drill plan is being fetched. Lets the pronunciation step
     * show a loader only while it is really loading, instead of breathing forever
     * when the plan comes back empty.
     */
    val drillsLoading: Boolean = false,
    /** One score per drill attempted, for the summary. */
    val drillScores: List<Int> = emptyList(),
    /** The score of the previous take on the current drill, or null on the first. */
    val previousScore: Int? = null,
    /** True when the open lesson is being served from a downloaded pack. */
    val offlineActive: Boolean = false,
    /**
     * True while the open lesson's content is being fetched. Distinct from
     * [loading], which the catalog refresh also flips: the player uses this to
     * breathe while it resolves, instead of showing an empty base with a dead
     * button for as long as an offline request takes to fail.
     */
    val opening: Boolean = false,
    /**
     * Why the open lesson's content could not be loaded. Distinct from [error],
     * which is also set by the catalog and download flows: this one names an
     * empty lesson player, so the player can explain the dead end instead of
     * showing a blank base with a disabled button.
     */
    val lessonError: String? = null,
    /** True when [lessonError] is because there is no connection. */
    val lessonOffline: Boolean = false,
    /** Ids of lessons available with no network. */
    val offlineIds: Set<String> = emptySet(),
    /** Which lesson the sheet is estimating, and the estimate in bytes. */
    val estimatingFor: String? = null,
    val estimateFor: String? = null,
    val estimateBytes: Long? = null,
) {
    val drill: DrillModel? get() = drills.getOrNull(drillIndex)

    /** The phrases said from memory in [step]: the due ones in review, the dialogue's own in "your turn". */
    fun cardsOf(step: LessonStep): List<RecallCard> = when (step) {
        LessonStep.REVIEW -> reviewCards
        LessonStep.SPEAK -> speakCards
        else -> emptyList()
    }

    /** The phrases of the step being played. */
    val recallCards: List<RecallCard> get() = cardsOf(step)

    /** The phrase being said from memory. */
    val recallCard: RecallCard? get() = recallCards.getOrNull(speakIndex)

    /** The steps this lesson has, once its content says whether there is anything to say. */
    val steps: List<LessonStep>
        get() {
            val hasSpeak = if (baseReady) speakCards.isNotEmpty() else active?.dialogueId != null
            return lessonSteps(
                hasSpeak = hasSpeak,
                hasReview = reviewCards.isNotEmpty(),
                hasContext = active?.notes?.isNotEmpty() == true,
            )
        }
    val baseReady: Boolean
        get() = dialogue != null || story != null || article != null

    /**
     * True while the pronunciation step has no drill to show but may still be
     * loading one. The base load counts too: opening straight into DRILLS leaves
     * `loading` set while its plan is still pending, and that must not read as a
     * genuinely empty bank. Once loading has settled with no drills, this is false
     * and the honest empty state shows.
     */
    val drillsPending: Boolean
        get() = drillsLoading || (loading && drills.isEmpty())
}

class LessonViewModel(private val services: AppServices) : ViewModel() {

    private val api = appApi
    private val profiles = ProfileStore.get(services.context)
    private val platform = platformInfo(services.context)
    private val engine = PracticeEngine(
        api,
        viewModelScope,
        services.context,
        profiles,
        services.onDevice,
        exerciseKind = "lesson_drill",
        screen = "lesson",
        debugTools = services.config.debug,
    )

    /** When the open lesson was started, for its duration on completion. */
    private var lessonOpenedAt = 0L
    private val piece = PiecePlayer(engine.player, { VoicePreferences.accent }) { path -> sourceOf(path) }
    private val progress = keyValueStore(services.context, PROGRESS_FILE)
    private val recall = RecallStore.of(services.context, debugTools = services.config.debug)

    /** The shared pronunciation loop (recording, scoring, take, voice). */
    val attempt: StateFlow<AttemptState> = engine.state

    /** Live microphone level for the recording waveform, 0..1. */
    val level: StateFlow<Float> = engine.level

    /** True while reference audio or a take is playing. */
    val playing: StateFlow<Boolean> = engine.playing

    /** Which paragraph of the open story or article is playing, and its status. */
    val pieceState: StateFlow<PieceState> = piece.state

    private val _state = MutableStateFlow(LessonState())
    val state: StateFlow<LessonState> = _state.asStateFlow()

    init {
        piece.onUserPlay = { slow -> UsageEvents.audioPlayed("piece", slow) }
        piece.onPlaybackError = { UsageEvents.appError("audio_failed", "lesson") }
        // A line said from memory that was another phrase is a recall miss: it
        // is not a pronunciation score and it stays out of the sound profile.
        engine.judgesSounds = { feedback ->
            !_state.value.step.fromMemory || RecallVerdict.of(feedback).judgesPronunciation
        }
        engine.onScored = { feedback ->
            if (_state.value.step.fromMemory) {
                onSpeakScored(feedback)
            } else {
                _state.value = _state.value.copy(
                    drillScores = _state.value.drillScores + feedback.score,
                    previousScore = AttemptHistory.recordTake(_state.value.drill?.id, feedback.score),
                )
                ProgressStore.recordDrill(
                    feedback.score,
                    AchievementCatalog.takeSignals(profiles.current, profiles.history.value),
                )
            }
        }
        val snapshot = readProgressSnapshot(progress)
        val savedStep = savedLessonStep(progress.getString(ProgressKeys.LESSON_STEP))
        _state.value = _state.value.copy(
            resumeLessonId = snapshot.resumeLessonId,
            resumeStep = savedStep ?: LessonStep.BASE,
            // Open in the level the learner actually left: the lesson still in
            // progress wins, then the level they picked by hand, then the level
            // of the last finished lesson. Never blindly back to A1.
            level = resolveStartupLevel(
                resumeLessonId = snapshot.resumeLessonId,
                resumeLevel = snapshot.resumeLevel,
                lastCompletedLevel = snapshot.lastCompletedLevel,
                manualLevel = snapshot.manualLevel,
            ),
        )
        // Reload whenever the target language or the interface language changes
        // (the manifest's theme labels depend on the UI language, and the target
        // language re-points the whole content bank). The signal emits the
        // current pair immediately, so this is also the first load.
        viewModelScope.launch {
            contentReloadSignal(LanguagePreferences.code, UiLanguage.current).collect {
                profiles.setLanguage(LanguagePreferences.code.value)
                refresh()
            }
        }
    }

    fun setLevel(level: String) {
        // A deliberate choice is recorded, so it wins over the last completed
        // lesson on the next cold start.
        saveManualLevel(progress, level)
        _state.value = _state.value.copy(level = level)
        loadLessons()
    }

    /**
     * Re-reads the catalog on open: the manifest (levels, themes) and the lesson
     * list. Nothing is cached across launches, so newly published content shows
     * up without reinstalling. Falls back to downloaded packs when offline.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val manifest = runCatching { api.manifest() }.getOrNull()
            if (manifest != null) {
                _state.value = _state.value.copy(
                    levels = orderedLevels(manifest.levels),
                    themeLabels = manifest.themeLabels(platform.uiLanguage),
                    themeOrder = manifest.themes.map { it.id }.filter { it.isNotBlank() },
                )
            }
            // A manifest that predates level metadata (or an old deployment with
            // no manifest at all): derive the levels from the whole lesson bank.
            if (_state.value.levels.isEmpty()) {
                val all = runCatching { api.lessons(limit = 200) }.getOrNull()
                val derived = if (all != null) {
                    orderedLevels(all.map { it.level })
                } else {
                    derivedLevelsFromOffline()
                }
                if (derived.isNotEmpty()) {
                    _state.value = _state.value.copy(levels = derived)
                }
            }
            val levels = _state.value.levels
            val level = levelForReload(_state.value.level, levels)
            if (level != _state.value.level) setLevel(level) else loadLessons()
        }
    }

    fun loadLessons() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val level = _state.value.level
            val offlineIds = OfflineStore.state.value.downloadedIds
            runCatching { api.lessons(level = level.takeIf { it != "ALL" }) }
                .onSuccess { list ->
                    _state.value = _state.value.copy(
                        loading = false,
                        lessons = list,
                        offlineIds = offlineIds,
                        levels = _state.value.levels.ifEmpty { orderedLevels(list.map { it.level }) },
                    )
                }
                .onFailure { e ->
                    // No network: serve whatever was downloaded for this level.
                    val offline = OfflineStore.bundles().map { it.lesson }
                        .filter { level == "ALL" || it.level == level }
                    if (offline.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            loading = false,
                            lessons = offline.sortedWith(compareBy({ it.theme }, { it.level }, { it.id })),
                            offlineIds = offlineIds,
                            error = null,
                        )
                    } else {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = networkOrServerError(services.connectivity),
                            offlineIds = offlineIds,
                        )
                        UsageEvents.appError("content_failed", "lessons", e)
                    }
                }
        }
    }

    /** Fetches everything the lesson needs: its base, its exercises, its words. */
    fun openLesson(lesson: LessonModel, startStep: LessonStep = LessonStep.BASE) {
        // A retry reopens the same lesson; only a new one is a start.
        val reopening = _state.value.active?.id == lesson.id
        if (!reopening) {
            lessonOpenedAt = currentTimeMillis()
            Analytics.track("lesson_started", mapOf("lesson_id" to lesson.id, "cefr" to lesson.level))
        }
        viewModelScope.launch {
            engine.clearResult()
            // A lesson opened from its start first asks back what is due from
            // earlier ones, then sets the scene with its notes; resumed half-way,
            // it goes straight to where it was.
            val fromStart = startStep <= LessonStep.BASE
            val due = if (fromStart) {
                recall.due(LanguagePreferences.current.code, platform.uiLanguage)
            } else {
                emptyList()
            }
            val first = when {
                due.isNotEmpty() -> LessonStep.REVIEW
                fromStart && lesson.notes.isNotEmpty() -> LessonStep.CONTEXT
                else -> startStep.coerceAtLeast(LessonStep.BASE)
            }
            if (!reopening || _state.value.step != first) trackStep(lesson.id, first)
            _state.value = _state.value.copy(
                loading = true,
                error = null,
                active = lesson,
                step = first,
                reviewCards = due,
                scheduledForReview = 0,
                drills = emptyList(),
                drillIndex = 0,
                // Opening straight into the pronunciation step means its plan is
                // still pending: mark it loading now, so the step never renders a
                // false "empty bank" before ensureDrills() runs.
                drillsLoading = first == LessonStep.DRILLS,
                drillScores = emptyList(),
                previousScore = null,
                dialogue = null,
                speakCards = emptyList(),
                speakIndex = 0,
                speakRevealed = false,
                speakRecorded = false,
                speakVerdict = null,
                story = null,
                article = null,
                words = emptyList(),
                exercises = emptyList(),
                offlineActive = false,
                opening = true,
                lessonError = null,
                lessonOffline = false,
            )
            saveProgress(lesson.id, first.coerceAtLeast(LessonStep.BASE), lesson.level)

            // A downloaded pack wins: it is what makes a lesson usable offline.
            val bundle = OfflineStore.bundle(lesson.id)
            if (bundle != null) {
                _state.value = _state.value.copy(
                    loading = false,
                    dialogue = bundle.dialogue,
                    speakCards = speakPlan(lesson, bundle.dialogue),
                    story = bundle.story,
                    article = bundle.article,
                    exercises = bundle.exercises,
                    words = bundle.words,
                    drills = bundle.drills,
                    drillIndex = 0,
                    // The pack carries its drills: the pending load is satisfied.
                    drillsLoading = false,
                    offlineActive = true,
                    opening = false,
                    lessonError = null,
                    lessonOffline = false,
                )
                piece.load(baseAudio(bundle.dialogue, bundle.story, bundle.article))
                if (first == LessonStep.DRILLS && bundle.drills.isEmpty()) ensureDrills()
                skipSpeakWithoutLines()
                return@launch
            }

            try {
                coroutineScope {
                    val dialogue = lesson.dialogueId?.let { async { api.dialoguesByIds(listOf(it)) } }
                    val story = lesson.storyId?.let { async { api.storiesByIds(listOf(it)) } }
                    val article = lesson.articleId?.let { async { api.articlesByIds(listOf(it)) } }
                    val exercises = async { api.exercisesByIds(lesson.exerciseIds) }
                    val words = if (lesson.wordIds.isEmpty()) null else async { api.wordsByIds(lesson.wordIds) }

                    val baseDialogue = dialogue?.await()?.firstOrNull()
                    val baseStory = story?.await()?.firstOrNull()
                    val baseArticle = article?.await()?.firstOrNull()
                    _state.value = _state.value.copy(
                        loading = false,
                        opening = false,
                        dialogue = baseDialogue,
                        speakCards = speakPlan(lesson, baseDialogue),
                        story = baseStory,
                        article = baseArticle,
                        exercises = exercises.await(),
                        words = words?.await().orEmpty(),
                    )
                    piece.load(baseAudio(baseDialogue, baseStory, baseArticle))
                }
                if (first == LessonStep.DRILLS) ensureDrills()
                skipSpeakWithoutLines()
            } catch (e: Exception) {
                val offline = !services.connectivity.isOnline()
                // Clear the drill loader too, or the pronunciation step would
                // breathe forever instead of surfacing the error.
                _state.value = _state.value.copy(
                    loading = false,
                    drillsLoading = false,
                    opening = false,
                    lessonOffline = offline,
                    lessonError = getString(
                        if (offline) Res.string.lesson_unavailable_offline_note else Res.string.error_load_lesson,
                    ),
                )
                UsageEvents.appError("content_failed", "lesson", e)
            }
        }
    }

    fun resume() {
        val id = _state.value.resumeLessonId ?: return
        val step = _state.value.resumeStep
        if (step == LessonStep.SUMMARY) return
        val lesson = _state.value.lessons.firstOrNull { it.id == id } ?: return
        openLesson(lesson, startStep = step)
    }

    fun closeLesson() {
        val leaving = _state.value
        if (leaving.active != null && leaving.step != LessonStep.SUMMARY) {
            Analytics.track(
                "flow_abandoned",
                mapOf("flow" to "lesson", "step" to leaving.step.name.lowercase(), "reason" to "closed"),
            )
        }
        piece.clear()
        engine.release()
        engine.clearResult()
        _state.value = _state.value.copy(
            active = null,
            step = LessonStep.BASE,
            offlineActive = false,
            opening = false,
            lessonError = null,
            lessonOffline = false,
        )
        loadLessons()
    }

    /**
     * Re-fetches the open lesson's content. What the player's error state offers,
     * so a lesson that failed offline is one tap from working once the connection
     * is back, instead of forcing the learner to close and reopen it.
     */
    fun retryActiveLesson() {
        val lesson = _state.value.active ?: return
        // Keep the step the lesson was opening at, so a lesson resumed in
        // drills/exercises does not fall back to the top on a retry.
        openLesson(lesson, startStep = _state.value.step)
    }

    /** Downloads the open lesson so it can be studied with no connection. */
    fun downloadActiveLesson() {
        _state.value.active?.let { downloadLesson(it) }
    }

    /** Downloads the optional on-device scoring model, from the lesson player. */
    fun downloadOnDeviceModel() {
        viewModelScope.launch { services.onDevice.download(api) }
    }

    fun stepTo(step: LessonStep) {
        val previous = _state.value.step
        piece.stop()
        engine.clearResult()
        _state.value = _state.value.copy(step = step)
        val active = _state.value.active
        if (active != null && step != previous) trackStep(active.id, step)
        if (step == LessonStep.SUMMARY && previous != LessonStep.SUMMARY) {
            active?.id?.let { ProgressStore.recordLesson(it) }
            active?.let {
                Analytics.track(
                    "lesson_completed",
                    mapOf(
                        "lesson_id" to it.id,
                        "cefr" to it.level,
                        "duration_s" to ((currentTimeMillis() - lessonOpenedAt) / 1000).coerceIn(0, 86_400),
                    ),
                )
            }
            // Remember the level of the last finished lesson, so a cold start
            // with nothing in progress opens where the learner just left off.
            active?.level?.let { recordFinishedLesson(progress, it) }
        }
        if (step == LessonStep.SUMMARY) {
            clearProgress()
        } else if (active != null) {
            saveProgress(active.id, step.coerceAtLeast(LessonStep.BASE), active.level)
        }
        if (step == LessonStep.DRILLS) ensureDrills()
        if (step.fromMemory) {
            _state.value = _state.value.copy(speakIndex = 0, speakRevealed = false, speakVerdict = null, speakRecorded = false)
        }
    }

    /** Moves to the next drill, or ends the lesson when the last one is done. */
    fun advanceDrill() {
        val s = _state.value
        if (s.drills.isEmpty()) {
            stepTo(LessonStep.SUMMARY)
            return
        }
        if (s.drillIndex >= s.drills.size - 1) {
            stepTo(LessonStep.SUMMARY)
        } else {
            _state.value = s.copy(drillIndex = s.drillIndex + 1, previousScore = null)
            engine.clearResult()
        }
    }

    fun nextStep() {
        val current = _state.value.step
        // The first step this lesson has after the current one. Looking it up by
        // order, not by position in the list, keeps a step the lesson does not
        // have (a stale state) from restarting the lesson.
        val next = _state.value.steps.firstOrNull { it > current } ?: return
        stepTo(next)
    }

    // ------------------------------------------------------------- your turn

    private fun speakPlan(lesson: LessonModel, dialogue: DialogueModel?): List<RecallCard> =
        dialogue?.let { SpeakPlanner.cards(it, lesson.id, platform.uiLanguage) }.orEmpty()

    /**
     * A lesson resumed at the speaking step may no longer have one (the interface
     * language changed, so the lines have no prompt): go on to pronunciation.
     */
    private fun skipSpeakWithoutLines() {
        if (_state.value.step == LessonStep.SPEAK && _state.value.speakCards.isEmpty()) stepTo(LessonStep.DRILLS)
    }

    /** A scored take of the current line: reveal the phrase and let the learner hear it. */
    private fun onSpeakScored(feedback: PronunciationFeedback) {
        val verdict = RecallVerdict.of(feedback)
        trackRecall(verdict.name.lowercase())
        schedule(verdict)
        _state.value = _state.value.copy(speakRevealed = true, speakVerdict = verdict)
        playLine(slow = false)
    }

    /** Shows the phrase without a take, for a learner who cannot recall it; it is then read aloud. */
    fun revealSpeakLine() {
        if (_state.value.speakRevealed) return
        trackRecall("revealed")
        schedule(verdict = null)
        _state.value = _state.value.copy(speakRevealed = true)
        playLine(slow = false)
    }

    /** The first answer of this visit to the phrase, the one [schedule] records. */
    private fun trackRecall(verdict: String) {
        val s = _state.value
        if (s.speakRecorded || s.recallCard == null) return
        val step = if (s.step == LessonStep.REVIEW) "review" else "speak"
        Analytics.track("recall_answered", mapOf("verdict" to verdict, "step" to step))
    }

    private fun trackStep(lessonId: String, step: LessonStep) {
        Analytics.track("lesson_step", mapOf("lesson_id" to lessonId, "step" to step.name.lowercase()))
    }

    /**
     * Puts the current phrase on the review ladder with the outcome of its first
     * try from memory. Later tries of the same visit are practice and do not
     * move it: what counts is whether it came to mind unaided.
     */
    private fun schedule(verdict: RecallVerdict?) {
        val s = _state.value
        val card = s.recallCard ?: return
        if (s.speakRecorded) return
        recall.record(LanguagePreferences.current.code, card.copy(accent = card.accent.ifBlank { VoicePreferences.accent }), verdict)
        _state.value = s.copy(
            speakRecorded = true,
            scheduledForReview = s.scheduledForReview + if (s.step == LessonStep.SPEAK) 1 else 0,
        )
    }

    /** Hides the phrase again for another try from memory. */
    fun retrySpeakLine() {
        engine.clearResult()
        _state.value = _state.value.copy(speakRevealed = false, speakVerdict = null)
    }

    /** Moves to the next line, or on to the next step after the last one. */
    fun advanceSpeak() {
        val s = _state.value
        engine.clearResult()
        if (s.speakIndex >= s.recallCards.size - 1) {
            nextStep()
        } else {
            _state.value = s.copy(speakIndex = s.speakIndex + 1, speakRevealed = false, speakVerdict = null, speakRecorded = false)
        }
    }

    /** The learner asks to hear the line again. */
    fun playSpeakLine(slow: Boolean = false) {
        if (playLine(slow)) UsageEvents.audioPlayed("line", slow)
    }

    fun playSpeakPartner() {
        val played = _state.value.recallCard?.let { playClip(it.partnerAudio, slow = false, it.accent) } ?: false
        if (played) UsageEvents.audioPlayed("partner")
    }

    /** Reads the current line aloud; also played on its own once the line is revealed. */
    private fun playLine(slow: Boolean): Boolean =
        _state.value.recallCard?.let { playClip(it.audio, slow, it.accent) } ?: false

    /**
     * A dialogue line in its speaker's voice, whatever its gender. A review
     * card keeps the wording of the accent it was studied in, so it plays that
     * accent's clip: the text on screen and the voice must say the same words.
     */
    private fun playClip(audio: Map<String, String>, slow: Boolean, cardAccent: String = ""): Boolean {
        val accent = cardAccent.ifBlank { VoicePreferences.accent }
        val clip = audio.playbackFor(accent, slow) ?: return false
        engine.player.play(sourceOf(clip.path), speed = clip.rate)
        return true
    }

    /** The clips of the current line in the learner's accent, for the recorder's listen buttons. */
    fun speakAudio(): Map<String, String> {
        val card = _state.value.recallCard ?: return emptyMap()
        val accent = card.accent.ifBlank { VoicePreferences.accent }
        return card.audio.filterKeys { accentOf(it) == accent }.ifEmpty { card.audio }
    }

    /** Debug-only: makes every stored phrase due now, so the next lesson opens with a review. */
    fun debugMakeReviewDue() = recall.debugMakeDue(LanguagePreferences.current.code)

    // ---------------------------------------------------------------- offline

    /** Downloads this lesson's base, exercises, artwork, audio and drills. */
    fun downloadLesson(lesson: LessonModel) {
        if (OfflineStore.state.value.downloadingId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null)
            val bundle = runCatching { gatherBundle(lesson) }.getOrElse {
                _state.value = _state.value.copy(
                    error = getString(Res.string.error_prepare_offline),
                )
                return@launch
            }
            val ok = OfflineStore.download(bundle, api)
            _state.value = _state.value.copy(offlineIds = OfflineStore.state.value.downloadedIds)
            if (!ok) {
                _state.value = _state.value.copy(
                    error = OfflineStore.state.value.error
                        ?: getString(Res.string.error_download),
                )
            }
        }
    }

    fun deleteDownload(lessonId: String) {
        viewModelScope.launch {
            OfflineStore.delete(lessonId)
            _state.value = _state.value.copy(
                offlineIds = OfflineStore.state.value.downloadedIds,
                estimateBytes = null,
            )
        }
    }

    fun clearDownloads() {
        viewModelScope.launch {
            OfflineStore.clear()
            _state.value = _state.value.copy(offlineIds = emptySet(), estimateBytes = null)
        }
    }

    /** Asks the server for an approximate size before the learner commits. */
    fun requestEstimate(lesson: LessonModel) {
        // A size already computed for this lesson and content version is shown
        // at once: no metadata fetch, no probes, nothing to wait for.
        OfflineStore.cachedEstimate(lesson.id, api.contentVersion)?.let { cached ->
            _state.value = _state.value.copy(estimatingFor = null, estimateFor = lesson.id, estimateBytes = cached)
            return
        }
        _state.value = _state.value.copy(estimatingFor = lesson.id, estimateFor = lesson.id, estimateBytes = null)
        viewModelScope.launch {
            val bytes = runCatching { OfflineStore.estimate(gatherBundle(lesson), api) }.getOrNull()
            _state.value = _state.value.copy(estimatingFor = null, estimateFor = lesson.id, estimateBytes = bytes)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private suspend fun gatherBundle(lesson: LessonModel): OfflineBundle = coroutineScope {
        val dialogue = lesson.dialogueId?.let { async { api.dialoguesByIds(listOf(it)).firstOrNull() } }
        val story = lesson.storyId?.let { async { api.storiesByIds(listOf(it)).firstOrNull() } }
        val article = lesson.articleId?.let { async { api.articlesByIds(listOf(it)).firstOrNull() } }
        val exercises = async { api.exercisesByIds(lesson.exerciseIds) }
        val words = async { api.wordsByIds(lesson.wordIds) }
        val drills = async { planDrills(lesson) }
        OfflineBundle(
            lesson = lesson,
            dialogue = dialogue?.await(),
            story = story?.await(),
            article = article?.await(),
            words = words.await(),
            exercises = exercises.await(),
            drills = drills.await(),
        )
    }

    /**
     * Drill selection for the lesson: the learner's weak sounds intersected with
     * the lesson's own target sounds, inside the lesson's theme. Without a
     * profile yet, it falls back to the theme's drills so the lesson always has
     * a pronunciation step.
     */
    private fun ensureDrills() {
        val lesson = _state.value.active ?: return
        if (_state.value.drills.isNotEmpty()) {
            if (_state.value.drillsLoading) _state.value = _state.value.copy(drillsLoading = false)
            return
        }
        _state.value = _state.value.copy(drillsLoading = true)
        viewModelScope.launch {
            val drills = planDrills(lesson)
            // The learner may have moved to another lesson while the plan was in
            // flight; only the lesson still open may receive the result, and its
            // loader must clear either way.
            if (_state.value.active?.id != lesson.id) return@launch
            _state.value = _state.value.copy(
                drills = drills,
                drillIndex = 0,
                drillsLoading = false,
                previousScore = null,
            )
        }
    }

    private suspend fun planDrills(lesson: LessonModel): List<DrillModel> {
        profiles.await()
        val profile = engine.profile
        val plan = runCatching {
            if (profile != null && profile.stats.isNotEmpty()) {
                api.drillPlan(
                    DrillPlanRequest(
                        profile = profile,
                        limit = DRILLS_PER_LESSON,
                        themes = listOf(lesson.theme),
                        targets = lesson.drillTargets,
                        level = lesson.level,
                    ),
                ).drills
            } else {
                api.drillsByTheme(lesson.theme, lesson.level, DRILLS_PER_LESSON)
            }
        }.getOrElse {
            runCatching { api.drills(limit = DRILLS_PER_LESSON) }.getOrDefault(emptyList())
        }
        return plan.ifEmpty {
            runCatching { api.drillsByTheme(lesson.theme, null, DRILLS_PER_LESSON) }.getOrDefault(emptyList())
        }
    }

    // ---------------------------------------------------------------- playback

    /**
     * The clips of a lesson's base: a dialogue reads turn by turn, a reading
     * paragraph by paragraph. An empty list falls through to the next kind, so a
     * story with no audio never hides an article that has some.
     */
    private fun baseAudio(
        dialogue: DialogueModel?,
        story: StoryModel?,
        article: ArticleModel?,
    ): List<Map<String, String>> =
        listOfNotNull(
            dialogue?.turns?.map { it.audio }?.takeIf { audio -> audio.any { it.isNotEmpty() } },
            story?.paragraphAudio?.takeIf { audio -> audio.any { it.isNotEmpty() } },
            article?.paragraphAudio?.takeIf { audio -> audio.any { it.isNotEmpty() } },
        ).firstOrNull() ?: emptyList()

    /** Plays a reference asset: the downloaded file when present, else the URL. */
    private fun playAsset(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        engine.player.play(sourceOf(path))
        return true
    }

    /** Resolves an asset-relative path to a downloaded file or a remote URL. */
    private fun sourceOf(path: String): AudioSource = AssetResolver.audio(path, api)

    /** Big play/pause for the open dialogue, story or article. */
    fun toggleBasePlayback() = piece.toggle()

    /** Tapping a line or paragraph [index] plays the piece from there. */
    fun playBaseFrom(index: Int) = piece.playFrom(index)

    fun setSlowBase(slow: Boolean) = piece.setSlow(slow)

    fun playWordAudio(word: WordModel) {
        if (playAsset(word.audio.clipFor(VoicePreferences.accent))) UsageEvents.audioPlayed("word")
    }

    fun assetUrl(path: String?): String = api.assetUrl(path ?: "")

    fun play(slow: Boolean = false) {
        val drill = _state.value.drill ?: return
        val clip = drill.audio.playbackFor(VoicePreferences.accent, slow) ?: return
        engine.player.play(sourceOf(clip.path), speed = clip.rate)
        UsageEvents.audioPlayed("drill", slow)
    }

    fun playTake() = engine.playTake()

    fun toggleRecord() {
        if (_state.value.step.fromMemory) {
            _state.value.recallCard?.let { engine.toggle(it.text, it.ipa, accentIpa = it.accentIpa) }
            return
        }
        _state.value.drill?.let { engine.toggle(it.text, it.ipa, it.ipaVariants, referenceAudioPath(it.audio), it.accentIpa) }
    }

    /**
     * Debug-only: a take of the current line with no microphone. [right] injects
     * the line itself; otherwise another line of the dialogue, to see a recall miss.
     */
    fun debugInjectSpeak(right: Boolean) {
        val s = _state.value
        val card = s.recallCard ?: return
        val other = s.recallCards.firstOrNull { it.ipa != card.ipa }?.ipa ?: card.partnerText.ifBlank { "a" }
        val actual = if (right) card.ipa else other
        engine.debugInjectTake(card.text, card.ipa, actual.replace("ˈ", "").replace("ˌ", "").replace(" ", ""), card.accentIpa)
    }

    // ------------------------------------------------------------------ helpers

    private fun derivedLevelsFromOffline(): List<String> =
        orderedLevels(OfflineStore.state.value.metas.map { it.level })

    private fun saveProgress(lessonId: String, step: LessonStep, lessonLevel: String) {
        saveLessonProgress(progress, lessonId, step, lessonLevel)
        _state.value = _state.value.copy(resumeLessonId = lessonId, resumeStep = step)
    }

    private fun clearProgress() {
        clearLessonProgress(progress)
        _state.value = _state.value.copy(resumeLessonId = null, resumeStep = LessonStep.BASE)
    }

    override fun onCleared() {
        engine.release()
    }

    private companion object {
        const val DRILLS_PER_LESSON = 4
    }
}
