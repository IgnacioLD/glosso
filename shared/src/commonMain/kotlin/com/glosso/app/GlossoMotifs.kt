package com.glosso.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoType
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.levelColor
import com.glosso.app.resources.*
import com.glosso.engine.model.MasteryLevel
import com.glosso.engine.model.ScoringConfig
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The drawn motifs: the voice line as a wave, a score as a ring, a sound as a
 * dial. Everything here is code, not an asset, and reads the existing tokens
 * only (no new colors, no new type).
 */

/**
 * The level a bare 0-100 accuracy reads as. The bands are the engine's own
 * ([ScoringConfig]), so a number is colored the same on every screen and the
 * same way a scored word is.
 */
internal fun accuracyLevel(score: Int): MasteryLevel = ScoringConfig.levelFor(score.coerceIn(0, 100))

/** The accent a 0-100 accuracy earns. */
@Composable
@ReadOnlyComposable
internal fun accuracyColor(score: Int): Color = levelColor(accuracyLevel(score).name)

/**
 * A score as a ring: a hairline track and an arc that sweeps to the score when
 * it lands, the number counting up in the middle. The gap at the bottom makes it
 * read as a gauge, not a donut. TalkBack hears the final score once, not the
 * count-up.
 */
@Composable
internal fun ScoreRing(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    stroke: Dp = 8.dp,
    color: Color = accuracyColor(score),
) {
    val target = score.coerceIn(0, 100)
    val reduce = LocalReduceMotion.current
    val sweep = remember { Animatable(if (reduce) target / 100f else 0f) }
    LaunchedEffect(target, reduce) {
        if (reduce) {
            sweep.snapTo(target / 100f)
        } else {
            sweep.snapTo(0f)
            sweep.animateTo(target / 100f, Motion.reward)
        }
    }
    val track = MaterialTheme.colorScheme.outline
    val description = stringResource(Res.string.score_a11y, target)
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val topLeft = Offset(w / 2f, w / 2f)
            val arcSize = Size(this.size.width - w, this.size.height - w)
            val style = Stroke(width = w, cap = StrokeCap.Round)
            drawArc(track, RING_START, RING_SWEEP, false, topLeft, arcSize, style = style)
            val value = sweep.value.coerceIn(0f, 1f)
            if (value > 0f) {
                drawArc(color, RING_START, RING_SWEEP * value, false, topLeft, arcSize, style = style)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(sweep.value * 100).toInt().coerceIn(0, 100)}",
                style = GlossoType.NumberXL,
                color = color,
            )
            Text(
                text = stringResource(Res.string.score_out_of),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val RING_START = 135f
private const val RING_SWEEP = 270f

/**
 * One sound as a small dial: the IPA symbol inside a thin ring filled to its
 * accuracy, the percentage under it.
 */
@Composable
internal fun SoundDial(
    phoneme: String,
    accuracy: Int,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val color = accuracyColor(accuracy)
    val reduce = LocalReduceMotion.current
    val fill = animateFloatAsState(
        targetValue = accuracy.coerceIn(0, 100) / 100f,
        animationSpec = tween(if (reduce) 0 else Motion.slow, easing = Motion.decelerate),
        label = "dialFill",
    )
    val track = MaterialTheme.colorScheme.outline
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 4.dp.toPx()
                val topLeft = Offset(w / 2f, w / 2f)
                val arc = Size(this.size.width - w, this.size.height - w)
                drawArc(track, -90f, 360f, false, topLeft, arc, style = Stroke(w))
                drawArc(color, -90f, 360f * fill.value, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text(
                text = phoneme,
                style = GlossoType.MonoIPA,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.label_percent, accuracy),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * The brand voice line at rest: three sine waves under a soft envelope, drawn
 * behind a hero at low alpha. Deliberately still: the home screen is calm, and
 * a decoration that never stops animating costs a frame every 16 ms.
 */
@Composable
internal fun VoiceWave(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    alpha: Float = 0.3f,
) {
    Canvas(modifier) {
        val mid = size.height * 0.5f
        fun wave(amp: Float, freq: Float, shift: Float, strokeDp: Float, a: Float) {
            val path = Path()
            val steps = 64
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val envelope = sin(PI.toFloat() * t)
                val y = mid + amp * envelope * sin(freq * t * 2 * PI.toFloat() + shift)
                if (i == 0) path.moveTo(0f, y) else path.lineTo(size.width * t, y)
            }
            drawPath(path, color.copy(alpha = a), style = Stroke(strokeDp.dp.toPx(), cap = StrokeCap.Round))
        }
        wave(size.height * 0.34f, 2.2f, 0f, 1.6f, alpha)
        wave(size.height * 0.22f, 3.1f, 1f, 1.2f, alpha * 0.7f)
        wave(size.height * 0.12f, 4.4f, 2f, 1f, alpha * 0.5f)
    }
}

/** A warm brass wash from a corner, the one gradient heroes are allowed. */
@Composable
internal fun heroWash(): Brush = Brush.linearGradient(
    listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surface,
    ),
)

/**
 * Cards that give under the finger: a small press scale driven by
 * [interaction]. The scale is applied in the placement layer, so a press never
 * recomposes or re-measures anything, and it is off with reduce motion.
 */
internal fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.975f): Modifier =
    this then PressScaleElement(interaction, pressed)

private data class PressScaleElement(
    val interaction: InteractionSource,
    val pressed: Float,
) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(interaction, pressed)

    override fun update(node: PressScaleNode) = node.update(interaction, pressed)

    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["pressed"] = pressed
    }
}

private class PressScaleNode(
    private var interaction: InteractionSource,
    private var pressed: Float,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private val scale = Animatable(1f)
    private var collector: Job? = null

    override fun onAttach() = collect()

    fun update(interaction: InteractionSource, pressed: Float) {
        this.pressed = pressed
        if (interaction != this.interaction) {
            this.interaction = interaction
            collector?.cancel()
            if (isAttached) collect()
        }
    }

    private fun collect() {
        collector = coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            interaction.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> presses += event
                    is PressInteraction.Release -> presses -= event.press
                    is PressInteraction.Cancel -> presses -= event.press
                }
                val down = presses.isNotEmpty() && !currentValueOf(LocalReduceMotion)
                launch { scale.animateTo(if (down) pressed else 1f, Motion.pulse) }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }
}

/** How far a header has collapsed (0 open, 1 collapsed) after [offsetPx] of scroll. */
internal fun collapseFraction(offsetPx: Int, distancePx: Float): Float =
    if (distancePx <= 0f) 1f else (offsetPx / distancePx).coerceIn(0f, 1f)

/** The same for a lazy list: any item past the first means fully collapsed. */
internal fun collapseFraction(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, distancePx: Float): Float =
    if (firstVisibleIndex > 0) 1f else collapseFraction(firstVisibleOffsetPx, distancePx)

/**
 * How far a top header has collapsed for a scrolling column. Read it only in a
 * layout or draw lambda (see [TopHeader]), so scrolling never recomposes.
 */
@Composable
internal fun rememberCollapse(scroll: ScrollState, distance: Dp = COLLAPSE_DISTANCE): State<Float> {
    val px = with(LocalDensity.current) { distance.toPx() }
    return remember(scroll, px) { derivedStateOf { collapseFraction(scroll.value, px) } }
}

/** How far a top header has collapsed for a lazy list. */
@Composable
internal fun rememberCollapse(list: LazyListState, distance: Dp = COLLAPSE_DISTANCE): State<Float> {
    val px = with(LocalDensity.current) { distance.toPx() }
    return remember(list, px) {
        derivedStateOf { collapseFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, px) }
    }
}

private val COLLAPSE_DISTANCE = 96.dp
