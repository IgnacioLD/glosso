package com.glosso.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.GlossoFonts
import com.glosso.app.designsystem.GlossoType
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The shared visual vocabulary. Small on purpose: one header pair, one hairline,
 * one primary action and a handful of states. Everything reads from the design
 * tokens in com.glosso.app.designsystem.
 */

/**
 * The owner's logo: two speech bubbles reading as a spoken exchange, drawn in
 * the theme's text colour over cobalt so it holds on both ink and paper.
 */
@Composable
internal fun GlossoLogo(size: Dp = 30.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.ic_glosso_symbol),
        contentDescription = null,
        modifier = modifier.height(size).aspectRatio(501f / 418f),
    )
}

/** The top-left lockup: the logo beside the GLOSSO wordmark. */
@Composable
internal fun GlossoWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GlossoLogo(size = 30.dp)
        Spacer(Modifier.width(Space.x12))
        WordmarkText()
    }
}

@Composable
private fun WordmarkText(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.glosso_wordmark).lowercase(),
        style = MaterialTheme.typography.titleLarge,
        fontFamily = GlossoFonts.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        letterSpacing = (-0.6).sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** The single brand motif: a thin waveform. Used as rule, progress and chart. */
@Composable
internal fun VoiceLine(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp,
    amplitude: Float = 0.42f,
) {
    Canvas(modifier) {
        val mid = size.height / 2f
        val amp = size.height * amplitude
        val step = size.width / 12f
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(0f, mid)
        var up = true
        var x = step / 2f
        while (x < size.width) {
            val y = if (up) mid - amp else mid + amp
            val cx = x - step / 2f
            path.quadraticTo(cx, y, x, mid)
            up = !up
            x += step
        }
        drawPath(path, color = color, style = Stroke(width = strokeWidth.toPx()))
    }
}

/**
 * Top-level header: a slim brand row over a large serif title. As the page
 * scrolls ([collapse] goes 0 -> 1) the large title folds away and a compact
 * copy of it takes the wordmark's place in the brand row, so the learner always
 * knows where they are without the header eating the screen.
 *
 * [collapse] is a lambda read only in layout and draw, so scrolling moves the
 * header without recomposing it.
 */
@Composable
internal fun TopHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    collapse: () -> Float = { 0f },
    trailing: (@Composable () -> Unit)? = null,
) {
    val shift = with(LocalDensity.current) { Space.x12.toPx() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.gutter, top = Space.x8),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlossoLogo(size = 22.dp)
            Spacer(Modifier.width(Space.x8))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                WordmarkText(
                    Modifier.graphicsLayer {
                        val c = collapse().coerceIn(0f, 1f)
                        alpha = 1f - c
                        translationY = -c * shift
                    },
                )
                // The compact title only mirrors the large one for the eye;
                // TalkBack reads the large title, once.
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .graphicsLayer {
                            val c = collapse().coerceIn(0f, 1f)
                            alpha = c
                            translationY = (1f - c) * shift
                        },
                )
            }
            trailing?.invoke()
        }
        // The large title folds by clipping its measured height, so the list
        // below rises with it instead of jumping when it disappears.
        Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val h = (placeable.height * (1f - collapse().coerceIn(0f, 1f))).roundToInt()
                    layout(placeable.width, h) {
                        placeable.placeRelative(0, h - placeable.height)
                    }
                }
                .graphicsLayer { alpha = (1f - collapse() * 1.6f).coerceIn(0f, 1f) },
        ) {
            Column(Modifier.padding(top = Space.x8, bottom = Space.x4)) {
                if (eyebrow != null) {
                    Eyebrow(eyebrow)
                    Spacer(Modifier.height(Space.x8))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(Space.x4))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // A hairline appears only once content scrolls under the header.
        Box(
            Modifier
                .padding(top = Space.x4)
                .fillMaxWidth()
                .height(1.dp)
                .graphicsLayer { alpha = collapse() }
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

/**
 * The bottom padding a full-window list needs: its own breathing room plus the
 * system navigation bar, so the last row can scroll clear of the gesture handle
 * or the three-button bar instead of ending under it.
 */
@Composable
internal fun listBottomPadding(extra: Dp = Space.x40): Dp =
    extra + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Sub-screen header: a back affordance and a serif title. */
@Composable
fun SubHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x8, vertical = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.back),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * Content scrolled under a fixed header, or on under a pinned footer (the
 * record bar, the tab bar), dissolves over [height] instead of being cut on a
 * hard line. Each fade only exists while there is something on that side to
 * scroll to, so a list at rest shows its first item untouched and its end says
 * there is more below.
 */
internal fun Modifier.scrollEdgeFade(state: ScrollableState, height: Dp = Space.x16): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = height.toPx()
            if (state.canScrollBackward) {
                drawRect(
                    brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade),
                    size = Size(size.width, fade),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.canScrollForward) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startY = size.height - fade,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fade),
                    size = Size(size.width, fade),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

@Composable
fun Hairline(modifier: Modifier = Modifier, gold: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                if (gold) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outline,
            ),
    )
}

/**
 * The one way art enters the app, with a sober fallback. [model] is a resolved
 * Coil model; when absent the brand shows: a warm gold-on-black wash with the
 * Glosso mark, inside a fixed 16:9 frame so an item never reads as broken.
 */
@Composable
internal fun GlossoArtwork(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.l),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            GlossoMark()
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                colorFilter = artworkDuotone,
                loading = { GlossoMark() },
                error = { GlossoMark() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Content art is monochrome line work on ink. A duotone maps its luminance
 * from ink to light cobalt, so every illustration (drawn in the old gold)
 * speaks the current palette without re-rendering the catalogue.
 */
private val artworkDuotone: ColorFilter = run {
    val shadow = floatArrayOf(16f, 16f, 22f)
    // The art's line colour (luminance ~169 of 255) lands exactly here; paler
    // strokes clip to white.
    val line = floatArrayOf(150f, 168f, 255f)
    val lineLuminance = 169f
    val weights = floatArrayOf(0.299f, 0.587f, 0.114f)
    val m = FloatArray(20)
    for (c in 0..2) {
        val k = (line[c] - shadow[c]) / lineLuminance
        for (w in 0..2) m[c * 5 + w] = k * weights[w]
        m[c * 5 + 4] = shadow[c]
    }
    m[18] = 1f
    ColorFilter.colorMatrix(ColorMatrix(m))
}

@Composable
private fun GlossoMark() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(Res.drawable.ic_glosso_symbol),
            contentDescription = null,
            modifier = Modifier.height(44.dp).aspectRatio(501f / 418f).alpha(0.85f),
        )
    }
}

/**
 * A section heading inside a screen: sentence case, bold, in the display face,
 * like a chapter title rather than a form label.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = color,
        modifier = modifier.semantics { heading() },
    )
}

/** A small kicker above a title (a date, "Your lesson", a unit name). */
@Composable
internal fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = GlossoColors.accentText,
) {
    Text(
        text = text,
        style = GlossoType.Eyebrow,
        color = color,
        modifier = modifier,
    )
}

/** A hairline track with a gold fill, animated. The line of voice as data. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val animated = animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(Motion.base, easing = Motion.standard),
        label = "progress",
    )
    ProgressTrack(progress = { animated.value }, modifier = modifier)
}

/**
 * The same track driven by a live value (a scroll position). [progress] is read
 * only in the graphics layer, so it follows the finger without recomposing.
 */
@Composable
internal fun ProgressTrack(progress: () -> Float, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.full)
    val fill = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Scale on the graphics layer, not the layout, so the bar updates without
        // re-measuring every frame. The fill must come after graphicsLayer: a
        // background drawn before it stays outside the layer and never scales,
        // which pins the bar full whatever the fraction.
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(shape)
                .graphicsLayer {
                    scaleX = progress().coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(fill),
        )
    }
}

/** How far through a scrolled text the reader is, 0 to 1 (a text that fits is unread). */
internal fun readFraction(scrolled: Int, max: Int): Float =
    if (max <= 0) 0f else (scrolled.toFloat() / max).coerceIn(0f, 1f)

/**
 * Discrete progress through a flow of [total] steps: one short segment per step,
 * gold up to and including [current] (0-based). Reads as "where am I" where a
 * continuous [ProgressLine] reads as "how much is left", so the two can stack
 * (lesson step above, exercise progress below) without looking like one bar
 * drawn twice.
 */
@Composable
internal fun StepProgress(total: Int, current: Int, modifier: Modifier = Modifier) {
    val done = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x4)) {
        repeat(total.coerceAtLeast(1)) { index ->
            val color by animateColorAsState(
                targetValue = if (index <= current) done else track,
                animationSpec = tween(Motion.base, easing = Motion.standard),
                label = "stepSegment",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(Radius.full))
                    .background(color),
            )
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) 0.96f else 1f,
        animationSpec = Motion.pulse,
        label = "buttonPress",
    )
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        interactionSource = interaction,
        shape = RoundedCornerShape(Radius.full),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = Space.x16, vertical = Space.x8),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.button)
            .pointerHoverIcon(PointerIcon.Hand)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The tonal partner of [PrimaryButton]: same height, same pill, same press
 * scale, a neutral fill instead of the accent. For the second action of a screen (listen, replay, an
 * optional download) so it never competes with the one primary action.
 */
@Composable
internal fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) 0.96f else 1f,
        animationSpec = Motion.pulse,
        label = "secondaryPress",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(Radius.full),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = Space.x16, vertical = Space.x8),
        modifier = modifier
            .heightIn(min = Sizes.button)
            .pointerHoverIcon(PointerIcon.Hand)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        content = content,
    )
}

/**
 * The edge of a card. On ink a filled surface already separates itself, so a
 * card has no outline; on paper a white card needs a hairline to hold.
 */
@Composable
internal fun cardBorder(): BorderStroke? =
    if (GlossoColors.isLight) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

/** Text-field colours: the focused label and caret in the AA accent tint. */
@Composable
internal fun glossoTextFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedLabelColor = GlossoColors.accentText,
    focusedBorderColor = GlossoColors.accentText,
    cursorColor = GlossoColors.accentText,
)

/** Secondary actions stay muted; the accent is reserved for the one primary action. */
@Composable
fun QuietTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The one place the primary action of a flow lives: a full-width bar pinned to
 * the bottom of the screen, above the navigation bar, with the same hairline,
 * gutter and vertical padding everywhere. Lessons, exercises, drills and their
 * "done" screens all put their Continue here, so the learner never has to hunt
 * for it and it never moves between screens.
 *
 * [navigationInset] adds the navigation bar inset so the bar clears the system
 * bar on a full screen. Set it to false when the bar already sits inside a
 * scrolling screen that applies that inset itself, so the padding is not added
 * twice.
 */
@Composable
internal fun BottomActionBar(
    modifier: Modifier = Modifier,
    navigationInset: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (navigationInset) Modifier.navigationBarsPadding() else Modifier)
                    .padding(horizontal = Space.gutter, vertical = Space.x12),
            ) {
                content()
            }
        }
    }
}

/** An icon on a small tinted tile, the leading mark of a navigation row. */
@Composable
internal fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Radius.s))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = GlossoColors.accentText,
            modifier = Modifier.size(Sizes.icon),
        )
    }
}

/** A quiet row for the Library hub: icon tile, title, one line, chevron. */
@Composable
internal fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(vertical = Space.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(Space.x16))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x2))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Space.x12))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A calm breath of the brand mark, not a spinning wheel.
            BreathingMark(size = 48.dp)
            if (label != null) {
                Spacer(Modifier.height(Space.x16))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A placeholder block that respects the layout, so the app does not jump. */
@Composable
internal fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.m),
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier
            .clip(shape)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
internal fun ErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    title: String? = null,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = Space.x32),
        ) {
            StateMark(Icons.Outlined.CloudOff, tint = GlossoColors.danger)
            Spacer(Modifier.height(Space.x20))
            Text(
                text = title ?: stringResource(Res.string.error_load_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x4))
            Text(
                text = detail ?: stringResource(Res.string.error_load_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.x24))
            PrimaryButton(
                text = stringResource(Res.string.retry),
                onClick = onRetry,
                modifier = Modifier.width(180.dp),
            )
        }
    }
}

/** The icon of an empty or error state, on a soft disc of its own tint. */
@Composable
private fun StateMark(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(72.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
    }
}

@Composable
internal fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.x4),
            modifier = Modifier.padding(horizontal = Space.x32),
        ) {
            if (icon != null) {
                StateMark(icon, tint = GlossoColors.accentText)
                Spacer(Modifier.height(Space.x16))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(Space.x16))
                action()
            }
        }
    }
}

/**
 * The one sober strip that says the app is offline. It sits as a sibling above
 * the navigation bar so it never covers content or an action, and it tells the
 * truth without an error tone: downloaded lessons still work, the rest waits.
 */
@Composable
internal fun OfflineBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    nudge: (@Composable () -> Unit)? = null,
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) {
            EnterTransition.None
        } else {
            slideInVertically(tween(Motion.fast, easing = Motion.decelerate)) { it / 2 } +
                fadeIn(tween(Motion.fast))
        },
        exit = if (reduce) {
            ExitTransition.None
        } else {
            slideOutVertically(tween(Motion.fast, easing = Motion.accelerate)) { it / 2 } +
                fadeOut(tween(Motion.fast))
        },
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Hairline()
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.gutter, vertical = Space.x8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Sizes.iconSmall),
                        )
                        Spacer(Modifier.width(Space.x12))
                        Text(
                            text = stringResource(Res.string.offline_banner),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    nudge?.invoke()
                }
            }
        }
    }
}

/**
 * A short reward when something real happens: an achievement unlocks or a sound
 * is mastered. It drops in with a spring, the seal stamps itself, and it leaves
 * on its own. Reduce motion collapses it to a plain fade.
 */
@Composable
internal fun AchievementOverlay(modifier: Modifier = Modifier) {
    val celebration by ProgressStore.celebration.collectAsState()
    val visible = celebration != null
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf<Celebration?>(null) }
    LaunchedEffect(celebration) {
        if (celebration != null) {
            shown = celebration
            kotlinx.coroutines.delay(2800)
            ProgressStore.consumeCelebration()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) {
            fadeIn(tween(0))
        } else {
            fadeIn(tween(Motion.base)) +
                slideInVertically(tween(Motion.medium)) { -it } +
                scaleIn(initialScale = 0.85f, animationSpec = Motion.reward)
        },
        exit = if (reduce) {
            fadeOut(tween(0))
        } else {
            fadeOut(tween(Motion.fast)) + slideOutVertically(tween(Motion.fast)) { -it / 2 }
        },
        modifier = modifier.statusBarsPadding(),
    ) {
        val moment = shown ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(Radius.l),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.x12),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Space.x16, vertical = Space.x12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SealMark(unlocked = true, size = 40.dp)
                Spacer(Modifier.width(Space.x12))
                Column {
                    Text(
                        text = stringResource(
                            if (moment.kind == CelebrationKind.ACHIEVEMENT) Res.string.celebration_achievement
                            else Res.string.celebration_mastery,
                        ),
                        style = GlossoType.Eyebrow,
                        color = GlossoColors.accentText,
                    )
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        text = stringResource(moment.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        text = stringResource(moment.detailRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One ring of the right-answer burst: how far it has grown and how faded it is. */
internal data class CelebrationRing(val radiusFraction: Float, val alpha: Float)

/** The stagger between the two burst rings, as a fraction of the whole burst. */
private const val CELEBRATION_STAGGER = 0.18f

/**
 * The two rings of the right-answer burst at [progress] (0..1). Ring 0 starts at
 * zero and ring 1 [CELEBRATION_STAGGER] later; each ring runs for the same span,
 * so ring 0 fades out before the burst ends and ring 1 fades out exactly when it
 * completes. A ring exists only while it is mid-flight, so when the burst ends
 * nothing is left painted.
 */
internal fun celebrationRings(progress: Float): List<CelebrationRing> {
    val p = progress.coerceIn(0f, 1f)
    val span = (1f - CELEBRATION_STAGGER).coerceAtLeast(0.0001f)
    return (0..1).mapNotNull { i ->
        val local = ((p - i * CELEBRATION_STAGGER) / span).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) null else CelebrationRing(local, (1f - local) * 0.4f)
    }
}

/**
 * The short reward on a right answer: two rings expanding from the middle, in
 * the success color. It is a small burst behind the card, never a screen-wide
 * ring, and it always finishes: once the answer has been read no ring is left.
 */
@Composable
internal fun CorrectCelebration(active: Boolean, modifier: Modifier = Modifier) {
    val color = GlossoColors.success
    val burst = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            burst.snapTo(0f)
            burst.animateTo(1f, animationSpec = tween(650))
        } else {
            burst.snapTo(0f)
        }
    }
    if (!active) return

    Canvas(modifier) {
        val maxRadius = CelebrationBurstRadius.toPx()
            .coerceAtMost(size.minDimension * 0.5f)
        celebrationRings(burst.value).forEach { ring ->
            drawCircle(
                color = color.copy(alpha = ring.alpha),
                radius = maxRadius * ring.radiusFraction,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** Ceiling for the burst radius so the ring stays a small accent, not a screen. */
private val CelebrationBurstRadius = 96.dp
