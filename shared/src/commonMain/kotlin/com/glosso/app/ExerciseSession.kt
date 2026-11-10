package com.glosso.app

import com.glosso.app.platform.rememberHaptics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.profile.SkillProfile
import org.jetbrains.compose.resources.stringResource

/**
 * A run through a set of exercises. Answer right and the exercise is done;
 * answer wrong and it goes to the back of the queue to be tried again, until it
 * is answered right or missed [maxAttempts] times. Nothing is silently skipped.
 */
class ExerciseSession(exercises: List<ExerciseModel>, private val maxAttempts: Int = 3) {

    private val byId = exercises.associateBy { it.id }
    private val orderNumber = exercises.mapIndexed { index, e -> e.id to index + 1 }.toMap()
    private val queue = ArrayDeque(exercises.map { it.id })
    private val misses = HashMap<String, Int>()

    var current by mutableStateOf(exercises.firstOrNull()); private set
    var revealed by mutableStateOf(false); private set
    var lastCorrect by mutableStateOf(false); private set
    /** Bumps on every advance so re-queued exercises get fresh input state. */
    var round by mutableIntStateOf(0); private set
    /** Number of exercises answered right plus those that hit the miss limit. */
    var settled by mutableIntStateOf(0); private set

    val total = exercises.size
    val progress: Float get() = if (total == 0) 0f else settled.toFloat() / total
    val number: Int get() = orderNumber[current?.id] ?: 1
    val isRetry: Boolean get() = current != null && (misses[current!!.id] ?: 0) > 0

    fun submit(correct: Boolean) {
        if (revealed) return
        lastCorrect = correct
        revealed = true
    }

    fun advance() {
        if (!revealed) return
        val id = queue.firstOrNull() ?: return
        queue.removeFirst()
        if (lastCorrect) {
            settled++
        } else {
            val missed = (misses[id] ?: 0) + 1
            misses[id] = missed
            if (missed >= maxAttempts) settled++ else queue.addLast(id)
        }
        revealed = false
        lastCorrect = false
        round++
        current = queue.firstOrNull()?.let { byId[it] }
    }
}

/**
 * Runs one exercise at a time with immediate feedback. Nothing advances on its
 * own: after an answer the verdict stays until the learner reads it and taps
 * Continue, so the feedback has time to teach. Calls [onComplete] when the whole
 * set is settled.
 *
 * The runner owns its shell: the card scrolls in the space it is given, while
 * Continue sits in the shared [BottomActionBar] pinned to the bottom. On a full
 * screen (a lesson, the exercise library) the bar is sticky. Set [embedded] when
 * the runner lives inside an already-scrolling document (a story reader): the
 * card then flows with that scroll instead of opening its own, and the bar
 * follows the card. Either way the action is in the same place in its bar.
 */
@Composable
internal fun ExerciseRunner(
    exercises: List<ExerciseModel>,
    modifier: Modifier = Modifier,
    onComplete: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Space.gutter),
    embedded: Boolean = false,
    /**
     * Names the base each exercise answers from, when the surrounding screen
     * knows it. The lesson player and the story reader leave this null: the
     * learner is already reading the base right there.
     */
    sourceOf: ((ExerciseModel) -> ExerciseSource?)? = null,
    onOpenSource: ((ExerciseSource) -> Unit)? = null,
) {
    var sessionKey by remember { mutableIntStateOf(0) }
    val session = remember(exercises, sessionKey) { ExerciseSession(exercises) }
    val haptics = rememberHaptics()

    val current = session.current
    val reduce = LocalReduceMotion.current
    val scrollState = rememberScrollState()

    // Every new card starts at the top: without this the next exercise lands
    // mid-scroll, at the height the last one left behind.
    LaunchedEffect(current?.id, session.round) { scrollState.scrollTo(0) }

    // The verdict lives at the end of the card; without this it can sit under
    // the fold while Continue is on screen, so the learner would advance before
    // reading it. Scroll it into view when the answer is revealed.
    LaunchedEffect(session.revealed) {
        if (session.revealed) scrollState.animateScrollTo(scrollState.maxValue)
    }

    val content: @Composable () -> Unit = {
        if (current != null) {
            ProgressLine(session.progress)
            Spacer(Modifier.height(Space.x20))
            AnimatedContent(
                targetState = session.current to session.round,
                transitionSpec = {
                    if (reduce) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        val dir = if (session.isRetry) -1 else 1
                        (
                            slideInHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) { width -> dir * width } +
                                fadeIn(tween(Motion.base, easing = Motion.decelerate))
                            ) togetherWith fadeOut(tween(120))
                    }
                },
                label = "exerciseStep",
            ) { (exercise, round) ->
                if (exercise == null) return@AnimatedContent
                ExerciseCard(
                    exercise = exercise,
                    number = session.number,
                    revealed = session.revealed,
                    correct = session.lastCorrect,
                    resetKey = round,
                    retry = session.isRetry,
                    source = sourceOf?.invoke(exercise),
                    onOpenSource = onOpenSource,
                    onAnswer = { correct ->
                        session.submit(correct)
                        Analytics.track(
                            "exercise_completed",
                            mapOf("kind" to exercise.type.lowercase(), "mastery" to correct),
                        )
                        if (correct) FeedbackSounds.correct() else FeedbackSounds.wrong()
                        haptics.answer(correct)
                        ProgressStore.recordAnswer(
                            correct,
                            SkillProfile.dimensionForExerciseType(exercise.type)?.name,
                        )
                    },
                )
            }
            Spacer(Modifier.height(Space.x24))
        } else {
            CompletionBeat(Modifier.fillMaxWidth().padding(vertical = Space.x72))
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (embedded) {
            Column(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (current == null) {
                    // Outside the scroll: a scrolling column has no height to
                    // centre in, which left the beat stranded at the top.
                    CompletionBeat(Modifier.fillMaxSize().padding(contentPadding))
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .scrollEdgeFade(scrollState)
                            .verticalScroll(scrollState)
                            .padding(contentPadding),
                    ) {
                        content()
                    }
                }
            }
        }
        // The one action the learner wants after answering: continue. It is the
        // only way forward, and it waits for them.
        when {
            current != null && session.revealed -> BottomActionBar(navigationInset = !embedded) {
                PrimaryButton(
                    text = stringResource(Res.string.exercise_continue),
                    onClick = { session.advance() },
                )
            }

            current == null -> BottomActionBar(navigationInset = !embedded) {
                if (onComplete != null) {
                    PrimaryButton(
                        text = stringResource(Res.string.exercise_continue),
                        onClick = onComplete,
                    )
                } else {
                    PrimaryButton(
                        text = stringResource(Res.string.exercise_restart),
                        onClick = { sessionKey++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionBeat(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    var show by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) { show = true }
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(if (reduce) 0 else Motion.medium, easing = Motion.decelerate),
        label = "completionAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (show || reduce) 1f else 0.9f,
        animationSpec = Motion.reward,
        label = "completionScale",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
        ) {
            RewardSeal(size = 88.dp)
            Spacer(Modifier.height(Space.x8))
            Text(
                stringResource(Res.string.exercise_complete),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
