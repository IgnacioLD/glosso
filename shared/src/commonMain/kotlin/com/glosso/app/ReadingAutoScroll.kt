package com.glosso.app

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Keeps the paragraph being read inside the reading viewport, without ever
 * wrestling the scroll from the learner.
 *
 * The screens that read a piece lay their paragraphs in a plain scrolling
 * Column, so this tracks where each paragraph sits and, when the one in play
 * changes, scrolls the least amount that makes it visible. A paragraph that is
 * already on screen is left alone: nothing moves for a line that can be read
 * where it is.
 *
 * A touch or drag silences the follow for [USER_SCROLL_PAUSE_MS]; after that
 * quiet spell the next paragraph change picks following back up, so browsing
 * ahead is never punished with a yank back.
 */
@Stable
class ReadingAutoScroll(
    val scrollState: ScrollState,
    private val marginPx: Int,
) {
    private val itemCoordinates = mutableMapOf<Int, LayoutCoordinates>()
    private var contentCoordinates: LayoutCoordinates? = null
    /** Until when the learner's own scroll keeps the follow quiet (monotonic, like uptime). */
    private var silentUntil: TimeSource.Monotonic.ValueTimeMark? = null
    private var animating = 0

    fun onContentPositioned(coordinates: LayoutCoordinates) {
        contentCoordinates = coordinates
    }

    fun onItemPositioned(index: Int, coordinates: LayoutCoordinates) {
        itemCoordinates[index] = coordinates
    }

    fun onUserScroll() {
        silentUntil = TimeSource.Monotonic.markNow() + USER_SCROLL_PAUSE_MS.milliseconds
    }

    /** Scrolls the least needed to bring [index] back into view. */
    suspend fun follow(index: Int, spec: AnimationSpec<Float>) {
        // A scroll already running is the learner's drag/fling (which must not be
        // fought) or our own (which needs no help). If it is theirs, wait for it
        // to settle and reassess, instead of dropping this follow: the paragraph
        // that just came on must not be missed until the next change.
        if (scrollState.isScrollInProgress && animating == 0) {
            snapshotFlow { scrollState.isScrollInProgress }.first { !it }
        }
        if (silentUntil?.hasNotPassedNow() == true) return
        val item = itemCoordinates[index] ?: return
        val content = contentCoordinates ?: return
        if (!item.isAttached || !content.isAttached) return
        val viewport = scrollState.viewportSize
        if (viewport <= 0) return
        // The column reports its scrolled content, which moves up as the scroll
        // advances, so the difference is where the paragraph sits in the content.
        // Where it sits on screen is that minus what is already scrolled; taking
        // the content offset for the screen position scrolled further the further
        // down the reading was.
        val offsetInContent = (item.positionInRoot().y - content.positionInRoot().y).toInt()
        val top = viewportTop(offsetInContent, scrollState.value)
        val delta = readingScrollDelta(top, item.size.height, viewport, marginPx) ?: return
        val target = (scrollState.value + delta).coerceIn(0, scrollState.maxValue)
        if (target == scrollState.value) return
        animating++
        try {
            scrollState.animateScrollTo(target, spec)
        } finally {
            animating--
        }
    }

    companion object {
        /** How long a manual scroll silences the follow, in milliseconds. */
        const val USER_SCROLL_PAUSE_MS = 5_000L
    }
}

/** Where a paragraph sits in the viewport, from its offset in the scrolled content. */
fun viewportTop(offsetInContent: Int, scrolled: Int): Int = offsetInContent - scrolled

/**
 * The scroll delta that makes a paragraph visible, or null when it already is.
 * Split out pure so the geometry can be asserted without a screen.
 *
 * A paragraph that fits is kept whole inside the margins. One taller than the
 * viewport can never be shown whole, so its start is what must stay in view:
 * moving it to the bottom would push the opening line off screen.
 */
fun readingScrollDelta(itemTop: Int, itemHeight: Int, viewport: Int, margin: Int): Int? {
    val fits = itemHeight <= viewport - 2 * margin
    if (!fits) {
        return if (itemTop < margin || itemTop > viewport - margin) itemTop - margin else null
    }
    val bottom = itemTop + itemHeight
    return when {
        itemTop < margin -> itemTop - margin
        bottom > viewport - margin -> bottom - (viewport - margin)
        else -> null
    }
}

/**
 * Wires a scrollable reading Column to follow its playing paragraph: call
 * [onContentPositioned] from the scrolling Column (placed after `verticalScroll`,
 * so it reports the scrolled content) and [onItemPositioned] from each paragraph, then [AutoScrollToPlaying] to drive it.
 */
@Composable
fun rememberReadingAutoScroll(
    scrollState: ScrollState,
    margin: Dp = 12.dp,
): ReadingAutoScroll {
    val marginPx = with(LocalDensity.current) { margin.roundToPx() }
    val state = remember(scrollState) { ReadingAutoScroll(scrollState, marginPx) }
    LaunchedEffect(state) {
        scrollState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start,
                is DragInteraction.Stop,
                is DragInteraction.Cancel -> state.onUserScroll()
            }
        }
    }
    return state
}

/** Follows the paragraph in play; the caller owns the vertical scroll. */
@Composable
fun AutoScrollToPlaying(
    autoScroll: ReadingAutoScroll?,
    currentParagraph: Int?,
) {
    if (autoScroll == null) return
    val reduceMotion = LocalReduceMotion.current
    val spec: AnimationSpec<Float> = remember(reduceMotion) {
        if (reduceMotion) snap() else tween(durationMillis = Motion.medium, easing = Motion.decelerate)
    }
    LaunchedEffect(currentParagraph) {
        currentParagraph?.let { autoScroll.follow(it, spec) }
    }
}
