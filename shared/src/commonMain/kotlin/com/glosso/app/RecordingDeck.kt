package com.glosso.app

import com.glosso.app.platform.rememberPlatformActions
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.jetbrains.compose.resources.stringResource

/**
 * The pronunciation deck: everything the learner does with their voice, in one
 * pinned footer. Reference (normal, slow) on the left, the microphone in the
 * middle with the live level drawn as a halo around it, their own take on the
 * right. The body above keeps the phrase and the result, and nothing moves when
 * the state changes: the same controls sit in the same places before, during
 * and after a take. Once there is an outcome, [action] (the next step) sits
 * under the controls.
 */
@Composable
internal fun RecordingDeck(
    state: DeckState,
    level: () -> Float,
    onToggle: () -> Unit,
    onPlay: (slow: Boolean) -> Unit,
    onPlayTake: () -> Unit,
    navigationInset: Boolean = true,
    action: (@Composable () -> Unit)? = null,
) {
    BottomActionBar(navigationInset = navigationInset) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DeckControls(state = state, level = level, onToggle = onToggle, onPlay = onPlay, onPlayTake = onPlayTake)
            if (action != null) {
                Spacer(Modifier.height(Space.x12))
                action()
            }
        }
    }
}

/** Everything the deck shows, hoisted so each recorder only maps its own attempt state. */
@Immutable
internal data class DeckState(
    val recording: Boolean,
    val scoring: Boolean,
    val error: String?,
    val hasAudio: Boolean,
    val hasSlow: Boolean,
    val hasTake: Boolean,
    val playing: Boolean,
)

/**
 * The deck for one attempt at a phrase whose reference audio is [audio] (keys
 * ending in ":slow" are the slowed reading). Pure, so each recorder only passes
 * its own attempt and the mapping is tested once.
 */
internal fun deckState(attempt: AttemptState, audio: Map<String, String>, playing: Boolean) = DeckState(
    recording = attempt.recording,
    scoring = attempt.scoring,
    error = attempt.error,
    hasAudio = audio.isNotEmpty(),
    // A normal clip can always be slowed by rate, so any reference offers a slow listen.
    hasSlow = audio.isNotEmpty(),
    hasTake = attempt.hasTake,
    playing = playing,
)

/** Which reference the learner last started, so only that control shows it playing. */
private enum class DeckAudio { NORMAL, SLOW, TAKE }

/**
 * The deck's controls without the pinned bar around them, for a footer that
 * already has its own frame (the onboarding seed page).
 */
@Composable
internal fun DeckControls(
    state: DeckState,
    level: () -> Float,
    onToggle: () -> Unit,
    onPlay: (slow: Boolean) -> Unit,
    onPlayTake: () -> Unit,
) {
    var active by remember { mutableStateOf<DeckAudio?>(null) }
    LaunchedEffect(state.playing) { if (!state.playing) active = null }
    val mic = rememberMicAction(onToggle)
    val canPlay = state.hasAudio && !state.recording
    // With a keyboard (a desktop browser, a tablet), Space starts and stops a
    // take: the deck takes focus when it appears, so the key reaches it.
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    Column(
        Modifier
            .fillMaxWidth()
            .focusRequester(keys)
            .focusable()
            .onPreviewKeyEvent { event ->
                val space = event.key == Key.Spacebar && event.type == KeyEventType.KeyUp
                if (space && !state.scoring) mic.onMic()
                space
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DeckStatus(recording = state.recording, scoring = state.scoring)
        Spacer(Modifier.height(Space.x4))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Each control owns an equal share of its side, so a longer label
            // (German, a larger font) wraps under its own button instead of
            // pushing into its neighbour.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                DeckButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.GraphicEq,
                    label = stringResource(Res.string.practice_normal),
                    enabled = canPlay,
                    playing = state.playing && active == DeckAudio.NORMAL,
                    onClick = { active = DeckAudio.NORMAL; onPlay(false) },
                )
                if (state.hasSlow) {
                    DeckButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.SlowMotionVideo,
                        label = stringResource(Res.string.practice_slow),
                        enabled = canPlay,
                        playing = state.playing && active == DeckAudio.SLOW,
                        onClick = { active = DeckAudio.SLOW; onPlay(true) },
                    )
                }
            }
            HaloMic(
                recording = state.recording,
                scoring = state.scoring,
                level = level,
                onClick = mic.onMic,
            )
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                DeckButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Headphones,
                    label = stringResource(Res.string.practice_your_take),
                    enabled = state.hasTake && !state.recording,
                    playing = state.playing && active == DeckAudio.TAKE,
                    onClick = { active = DeckAudio.TAKE; onPlayTake() },
                )
            }
        }
        state.error?.let {
            Spacer(Modifier.height(Space.x8))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = GlossoColors.danger,
                textAlign = TextAlign.Center,
            )
        }
        if (mic.permissionDenied) {
            val actions = rememberPlatformActions()
            Spacer(Modifier.height(Space.x8))
            Text(
                stringResource(Res.string.practice_mic_permission),
                style = MaterialTheme.typography.bodySmall,
                color = GlossoColors.warning,
                textAlign = TextAlign.Center,
            )
            TextButton(colors = accentTextButtonColors(), onClick = { actions.openAppSettings() }) {
                Text(stringResource(Res.string.practice_mic_permission_action))
            }
        }
    }
}

/** One line that names the moment: say it, recording, listening. Cross-fades. */
@Composable
private fun DeckStatus(recording: Boolean, scoring: Boolean) {
    val text = when {
        scoring -> stringResource(Res.string.practice_listening)
        recording -> stringResource(Res.string.practice_recording)
        else -> stringResource(Res.string.practice_tap_say)
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(Motion.base)) togetherWith fadeOut(tween(Motion.fast)) },
        label = "deckStatus",
    ) { t ->
        Text(
            text = t,
            style = MaterialTheme.typography.labelLarge,
            color = if (recording) GlossoColors.accentText else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A round reference control with its name under it. */
@Composable
private fun DeckButton(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    label: String,
    enabled: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .widthIn(min = Sizes.touch)
            .alpha(if (enabled) 1f else 0.38f)
            .pressScale(interaction, pressed = 0.92f)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(Sizes.touch)
                .clip(CircleShape)
                .border(
                    1.dp,
                    if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                PlayingBars()
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(Sizes.iconSmall + 2.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.x4))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (playing) GlossoColors.accentText else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * The microphone with the voice drawn around it: while recording, a ring of
 * bars grows with the live level, the newest sample at twelve o'clock and the
 * history wrapping clockwise. While the take is being scored, a brass arc
 * circles the button instead of a spinner inside it. The level, the halo and
 * the spin are read only while drawing, so a live level redraws the ring and
 * never recomposes the deck.
 */
@Composable
private fun HaloMic(
    recording: Boolean,
    scoring: Boolean,
    level: () -> Float,
    onClick: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val currentLevel by rememberUpdatedState(level)
    var samples by remember { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(recording) {
        samples = emptyList()
        if (recording) snapshotFlow { currentLevel() }.collect { samples = haloHistory(samples, it) }
    }
    val presence = animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = tween(if (reduce) 0 else Motion.base),
        label = "haloPresence",
    )
    val spin = remember { Animatable(0f) }
    LaunchedEffect(scoring, reduce) {
        spin.snapTo(0f)
        if (scoring && !reduce) {
            spin.animateTo(360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart))
        }
    }
    val gold = GlossoColors.accentText
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(if (recording) Res.string.practice_stop_score else Res.string.practice_start_recording)
    Box(Modifier.size(HALO_SIZE), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val inner = MIC_SIZE.toPx() / 2f + 4.dp.toPx()
            val shown = presence.value
            if (shown > 0f) {
                val bars = samples
                val maxLen = 12.dp.toPx()
                for (i in 0 until HALO_BARS) {
                    val angle = (-90f + i * 360f / HALO_BARS) * (PI / 180f).toFloat()
                    val len = (2.dp.toPx() + (bars.getOrNull(i) ?: 0f) * maxLen) * shown
                    val dir = Offset(cos(angle), sin(angle))
                    drawLine(
                        color = gold.copy(alpha = (0.35f + 0.65f * (1f - i / HALO_BARS.toFloat())) * shown),
                        start = center + dir * inner,
                        end = center + dir * (inner + len),
                        strokeWidth = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                drawCircle(track, radius = inner + 2.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
            if (scoring) {
                val r = inner + 2.dp.toPx()
                rotate(spin.value, center) {
                    drawArc(
                        color = primary,
                        startAngle = -90f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(MIC_SIZE)
                .pressScale(interaction, pressed = 0.94f)
                .clip(CircleShape)
                .background(if (scoring) primary.copy(alpha = 0.5f) else primary)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !scoring,
                    role = Role.Button,
                    onClickLabel = label,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/** The halo's history after one more level: newest first, capped at [max] bars. */
internal fun haloHistory(history: List<Float>, level: Float, max: Int = HALO_BARS): List<Float> =
    (listOf(level.coerceIn(0f, 1f)) + history).take(max)

private const val HALO_BARS = 44
private val MIC_SIZE = 72.dp
private val HALO_SIZE = 104.dp
