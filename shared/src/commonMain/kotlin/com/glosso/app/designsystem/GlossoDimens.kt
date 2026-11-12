package com.glosso.app.designsystem

import androidx.compose.ui.unit.dp

/**
 * Spacing scale, 4 dp grid. Use these instead of ad-hoc dp values.
 */
object Space {
    val x2 = 2.dp
    val x4 = 4.dp
    val x8 = 8.dp
    val x12 = 12.dp
    val x16 = 16.dp
    val x20 = 20.dp
    val x24 = 24.dp
    val x32 = 32.dp
    val x40 = 40.dp
    val x56 = 56.dp
    val x72 = 72.dp

    /** Screen gutter on a phone; use [gutterWide] on tablets. */
    val gutter = x20
    val gutterWide = x32

    /** Width at which the layout switches to the wide gutter. */
    val wideBreakpoint = 600.dp

    /** Max reading column for stories and articles. */
    val readingWidth = 680.dp
}

/** Corner radii. Contained, never bubbly. */
object Radius {
    val xs = 6.dp
    val s = 10.dp
    val m = 14.dp
    val l = 20.dp
    val xl = 28.dp
    val full = 999.dp
}

/** Touch targets. Nothing interactive is smaller than [touch] (48 dp). */
object Sizes {
    val touch = 48.dp
    val touchPrimary = 56.dp
    val button = 56.dp
    val buttonSecondary = 52.dp
    val icon = 22.dp
    val iconSmall = 18.dp
    val mic = 80.dp
    val avatar = 56.dp
    val bottomBar = 64.dp
    val navRail = 96.dp

    /** From this window width (a tablet, a desktop browser) the bottom bar becomes a side rail. */
    val wideLayout = 600.dp

    /** The widest a screen's content gets in a wide window, so lines and cards keep a phone's reading width. */
    val contentMaxWidth = 640.dp
}

/**
 * Elevation tokens. On dark, hierarchy comes from surface + a 1 px gold edge,
 * not from shadows (they do not read on black). On light, warm shadows.
 */
object Elevation {
    val none = 0.dp
    val card = 0.dp
    val sheet = 8.dp
    val overlay = 16.dp
    const val shadowAlpha = 0.28f
}
