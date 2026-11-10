package com.glosso.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.GlossoFonts
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/** A whole, unlockable achievement rendered as a seal, not a list row. */
@Composable
internal fun AchievementSeal(
    title: String,
    detail: String,
    progress: Int,
    target: Int,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (unlocked) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = BorderStroke(
            1.dp,
            if (unlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.padding(Space.x16)) {
            SealMark(unlocked = unlocked, size = 40.dp)
            Spacer(Modifier.size(Space.x12))
            // A reserved block keeps every seal on a row the same height, so a
            // one-line title does not leave a shorter card next to a wrapped one.
            Column(Modifier.heightIn(min = 106.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (unlocked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(Space.x2))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(Space.x12))
            AchievementProgress(progress = progress, target = target, unlocked = unlocked)
        }
    }
}

/**
 * How far the learner is toward a seal, as X/Y with a thin bar. The fraction is
 * the real count, not a rounded feeling, so a locked seal still shows the work
 * left. The bar is decorative: the text carries the value for screen readers.
 */
@Composable
private fun AchievementProgress(progress: Int, target: Int, unlocked: Boolean) {
    val fraction = if (target <= 0) 1f else (progress.toFloat() / target).coerceIn(0f, 1f)
    val fill = if (unlocked) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(Radius.full))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics {},
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(Radius.full))
                    .background(fill),
            )
        }
        Spacer(Modifier.width(Space.x8))
        Text(
            text = stringResource(Res.string.achievement_progress, progress, target),
            style = MaterialTheme.typography.labelMedium,
            color = if (unlocked) GlossoColors.accentText
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The brand seal. Earned: a solid cobalt disc with the voice line stamped in
 * white, inside a soft halo ring, landing with a small spring. Locked: a quiet
 * grey disc.
 */
@Composable
internal fun SealMark(unlocked: Boolean, size: Dp) {
    val reduce = LocalReduceMotion.current
    // Starts where the current state wants it: a fresh reward begins at the dip
    // (no one-frame flash at full scale), and a seal that is not earned or is at
    // rest stays at 1f instead of being pinned at a scale it only ever reaches
    // mid-reward (which hid every locked seal).
    val active = remember { Animatable(if (unlocked && !reduce) 0.8f else 1f) }
    LaunchedEffect(unlocked, reduce) {
        if (unlocked && !reduce) {
            active.snapTo(0.8f)
            active.animateTo(1f, animationSpec = Motion.reward)
        } else {
            active.snapTo(1f)
        }
    }
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = active.value; scaleY = active.value }
            .clip(CircleShape)
            .background(
                if (unlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.76f)
                .clip(CircleShape)
                .background(
                    if (unlocked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (unlocked) {
                VoiceLine(
                    modifier = Modifier.size(size * 0.44f),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = (size.value / 28f).coerceIn(1.6f, 3.5f).dp,
                )
            }
        }
    }
}

/**
 * The seal as the centre of a completion screen: a soft cobalt glow behind it
 * and one ring that ripples out once when it lands. Calm, but unmistakably a
 * reward.
 */
@Composable
internal fun RewardSeal(size: Dp, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val ripple = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(reduce) {
        if (!reduce) ripple.animateTo(1f, animationSpec = tween(1100, easing = Motion.decelerate))
    }
    val glow = MaterialTheme.colorScheme.primary
    Box(modifier.size(size * 2f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.28f), glow.copy(alpha = 0f)),
                    center = center,
                    radius = this.size.minDimension / 2f,
                ),
            )
            val p = ripple.value
            if (p in 0.001f..0.999f) {
                drawCircle(
                    color = glow.copy(alpha = (1f - p) * 0.6f),
                    radius = this.size.minDimension / 4f * (1f + p),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        SealMark(unlocked = true, size = size)
    }
}

/**
 * The active daily streak as an honest number of days: a warm flame when the
 * streak is alive, a muted one at zero. Never a purchased freeze.
 */
@Composable
internal fun StreakChip(days: Int, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (reduce || days > 0) 1f else 0.96f,
        animationSpec = Motion.pulse,
        label = "streak",
    )
    val alive = days > 0
    Surface(
        color = if (alive) GlossoColors.ember.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.full),
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Row(
            modifier = Modifier.padding(start = Space.x8, end = Space.x12, top = Space.x4, bottom = Space.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.LocalFireDepartment,
                contentDescription = stringResource(Res.string.you_streak),
                tint = if (alive) GlossoColors.ember else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Space.x4))
            Text(
                text = "$days",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GlossoFonts.DisplayText,
                fontWeight = FontWeight.ExtraBold,
                color = if (alive) GlossoColors.ember else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The phase name (Escucha, Repite, Nota / Lee, Practica...) shows itself only at
 * the moment the phase changes and fades away on its own. It is never a
 * permanent chip on screen.
 */
@Composable
internal fun TransientPhaseIndicator(
    text: String,
    phaseKey: Any,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(phaseKey) {
        visible = true
        kotlinx.coroutines.delay(1500)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) {
            fadeIn(tween(0))
        } else {
            fadeIn(tween(Motion.base)) +
                scaleIn(initialScale = 0.85f, animationSpec = tween(Motion.base, easing = Motion.decelerate))
        },
        exit = if (reduce) {
            fadeOut(tween(0))
        } else {
            fadeOut(tween(Motion.fast)) +
                scaleOut(targetScale = 0.9f, animationSpec = tween(Motion.fast))
        },
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(Radius.full),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = Space.x16, vertical = Space.x8),
                style = MaterialTheme.typography.labelSmall,
                color = GlossoColors.accentText,
            )
        }
    }
}

/**
 * A soft entrance for content that should not pop in: fade and a small rise,
 * staggered by [index]. Collapses to instant when reduce motion is on.
 */
@Composable
internal fun StaggeredAppear(
    modifier: Modifier = Modifier,
    index: Int = 0,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) {
        if (!shown) {
            kotlinx.coroutines.delay((index.coerceAtMost(10) * 45).toLong())
            shown = true
        }
    }
    // States, not delegated values: they are read only in the graphics layer,
    // so the fade and rise never recompose the content they wrap.
    val alpha = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (reduce) 0 else Motion.medium, easing = Motion.decelerate),
        label = "staggerAlpha",
    )
    val rise = animateFloatAsState(
        targetValue = if (shown) 0f else 18f,
        animationSpec = tween(if (reduce) 0 else Motion.medium, easing = Motion.decelerate),
        label = "staggerRise",
    )
    Box(modifier.graphicsLayer { this.alpha = alpha.value; translationY = rise.value }) { content() }
}

/** A calm loader: the brand mark breathing, instead of a spinning wheel. */
@Composable
internal fun BreathingMark(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val reduce = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "breathe")
    val scale by transition.animateFloat(
        initialValue = if (reduce) 1f else 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = Motion.standard), RepeatMode.Reverse),
        label = "breatheScale",
    )
    val alpha by transition.animateFloat(
        initialValue = if (reduce) 0.85f else 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1500, easing = Motion.standard), RepeatMode.Reverse),
        label = "breatheAlpha",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        GlossoLogo(size = size)
    }
}

/** A small, quiet tag naming a lesson base (Diálogo, Historia, Noticia). */
@Composable
internal fun GlossoTag(label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.full),
        modifier = modifier,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = Space.x12, vertical = Space.x4),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
