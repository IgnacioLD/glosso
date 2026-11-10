package com.glosso.app

import com.glosso.app.platform.currentUiLanguage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.lesson.RecallVerdict
import org.jetbrains.compose.resources.stringResource

/**
 * "Your turn" and review: the learner plays one character of a dialogue and
 * says each line from memory, either the dialogue just studied or lines of
 * earlier lessons that are due. The other character's line sets the scene, the
 * prompt is in the learner's own language and the phrase stays hidden until a
 * take is scored (or the learner asks to see it). Then it is revealed and its
 * clip plays as confirmation. A take that was another phrase is a recall miss,
 * shown as such and never as a pronunciation score.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpeakStep(vm: LessonViewModel, state: LessonState, step: LessonStep) {
    val attempt by vm.attempt.collectAsState()
    val level = vm.level.collectAsState()
    val playing by vm.playing.collectAsState()
    val uiLanguage = currentUiLanguage()

    // The phrases of the step this page was composed for. While the page
    // animates out, the lesson is already on another step: reading the live one
    // here made the leaving page act on the next step's state.
    val cards = state.cardsOf(step)
    val card = cards.getOrNull(state.speakIndex)
    val review = step == LessonStep.REVIEW
    val hasOutcome = attempt.hasOutcome
    val scrollState = rememberScrollState()
    LaunchedEffect(state.speakIndex) { scrollState.scrollTo(0) }
    val resultRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(hasOutcome) { if (hasOutcome) resultRequester.bringIntoView() }

    // No phrase: the page is on its way out, or the lesson has nothing to say
    // (the step is then never entered). It draws nothing and moves nothing.
    if (card == null) return

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEdgeFade(scrollState)
                    .padding(horizontal = Space.gutter)
                    .verticalScroll(scrollState),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.speak_progress, state.speakIndex + 1, cards.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = GlossoColors.accentText,
                        modifier = Modifier.weight(1f),
                    )
                    if (!state.speakRevealed && !hasOutcome) {
                        QuietTextButton(
                            text = stringResource(Res.string.speak_show_phrase),
                            onClick = { vm.revealSpeakLine() },
                        )
                    }
                }
                if (review && state.speakIndex == 0 && !state.speakRevealed && !hasOutcome) {
                    // Review is an offer, not a toll: say it once, at the start,
                    // with the way past it right there.
                    Spacer(Modifier.height(Space.x8))
                    Text(
                        text = stringResource(Res.string.review_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    QuietTextButton(
                        text = stringResource(Res.string.review_skip),
                        onClick = { vm.nextStep() },
                    )
                }
                Spacer(Modifier.height(Space.x16))
                if (card.hasPartner) {
                    DialogueTurn(
                        alignEnd = false,
                        speaker = card.partnerSpeaker,
                        text = card.partnerText,
                        ipa = "",
                        translation = uiTranslation(card.partnerTranslations),
                        current = false,
                        playing = false,
                        hasAudio = card.partnerAudio.isNotEmpty(),
                        onPlay = { vm.playSpeakPartner() },
                    )
                    Spacer(Modifier.height(Space.x24))
                }
                // Without a translation into the interface language the line above
                // is the whole prompt: the learner recalls the answer they studied.
                val cue = card.cues[uiLanguage].orEmpty()
                Text(
                    text = if (cue.isBlank()) {
                        stringResource(Res.string.speak_from_memory)
                    } else {
                        stringResource(Res.string.speak_say_as, card.speaker)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = cue.ifBlank { stringResource(Res.string.speak_recall_question, card.speaker) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.x24))
                if (state.speakRevealed) {
                    DrillCard(
                        text = card.text,
                        ipa = card.displayPhonetic,
                        translation = null,
                        focus = emptyList(),
                        hasAudio = card.audio.isNotEmpty(),
                        onPlay = { vm.playSpeakLine() },
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.speak_hidden_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (attempt.offlineNoModel) {
                    Spacer(Modifier.height(Space.x32))
                    OnDeviceModelOffer(onDownload = { vm.downloadOnDeviceModel() })
                }
                Box(Modifier.bringIntoViewRequester(resultRequester)) {
                    Column {
                        val result = attempt.result
                        when {
                            attempt.notEvaluated -> {
                                Spacer(Modifier.height(Space.x32))
                                NotEvaluatedCard()
                            }

                            result != null && state.speakVerdict == RecallVerdict.NOT_THAT_PHRASE -> {
                                Spacer(Modifier.height(Space.x32))
                                RecallMissCard()
                            }

                            result != null -> {
                                Spacer(Modifier.height(Space.x32))
                                if (state.speakVerdict == RecallVerdict.PARTIAL) {
                                    Text(
                                        text = stringResource(Res.string.speak_partial),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(Space.x12))
                                }
                                ResultCard(result)
                            }
                        }
                        if (hasOutcome || state.speakRevealed) {
                            Spacer(Modifier.height(Space.x8))
                            QuietTextButton(
                                text = stringResource(Res.string.speak_try_again),
                                onClick = { vm.retrySpeakLine() },
                            )
                        }
                    }
                }
                if (LocalAppConfig.current.debugUi && !hasOutcome) {
                    Row {
                        QuietTextButton(text = "Test: the line", onClick = { vm.debugInjectSpeak(right = true) })
                        QuietTextButton(text = "Test: another line", onClick = { vm.debugInjectSpeak(right = false) })
                    }
                }
                Spacer(Modifier.height(Space.x24))
            }
        }
        // The listen buttons only exist once the phrase is revealed: before
        // that, hearing it would turn recall into repetition.
        RecordingDeck(
            state = deckState(attempt, if (state.speakRevealed) vm.speakAudio() else emptyMap(), playing),
            level = { level.value },
            onToggle = { vm.toggleRecord() },
            onPlay = { slow -> vm.playSpeakLine(slow) },
            onPlayTake = { vm.playTake() },
            action = if (hasOutcome || state.speakRevealed) {
                {
                    val last = state.speakIndex >= cards.size - 1
                    PrimaryButton(
                        text = stringResource(
                            when {
                                !last -> Res.string.speak_next
                                review -> Res.string.review_finish
                                else -> Res.string.speak_finish
                            },
                        ),
                        onClick = { vm.advanceSpeak() },
                    )
                }
            } else {
                null
            },
        )
    }
}

/** A calm card for a take that was another phrase: a recall miss, never a bad mark. */
@Composable
private fun RecallMissCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Space.x20), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(Sizes.touch - Space.x8)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Replay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.iconSmall),
                )
            }
            Spacer(Modifier.width(Space.x16))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.speak_miss_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.x4))
                Text(
                    text = stringResource(Res.string.speak_miss_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
