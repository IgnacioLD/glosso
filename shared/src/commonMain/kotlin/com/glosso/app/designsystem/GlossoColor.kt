package com.glosso.app.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Glosso brand palette: a neutral near-black, one confident cobalt and a warm
 * ember kept for the daily streak. Cobalt is the voice of the product (the one
 * action, progress, the lesson hero); everything else is neutral so the one
 * accent carries meaning. Success and error stay distinct in hue from cobalt,
 * so a result never reads as "brand colour". See docs/BRAND.md.
 *
 * These are the raw, theme-independent brand values. Screens should read the
 * semantic roles from [GlossoColors] instead of these constants.
 */
object BrandNight {
    val Ink = Color(0xFF0B0B0E)
    val Surface = Color(0xFF16161B)
    val Raised = Color(0xFF202027)
    val Line = Color(0xFF2B2B34)

    val Cobalt = Color(0xFF3D51E0)
    val CobaltLight = Color(0xFF93A4FF)
    val CobaltDeep = Color(0xFF1A1D38)

    val Text = Color(0xFFF4F4F6)
    val Muted = Color(0xFFA3A3AE)
}

/** Daylight: a cool off-white page with pure white cards. */
object BrandDay {
    val Paper = Color(0xFFF3F3F6)
    val Surface = Color(0xFFFFFFFF)
    val Raised = Color(0xFFE9E9EF)
    val Line = Color(0xFFDCDCE4)

    val Text = Color(0xFF0B0B0E)
    val Muted = Color(0xFF5D5D6A)
    val Cobalt = Color(0xFF2F48E0)
}

/**
 * Semantic roles beyond Material's color scheme: success, warning, danger, the
 * streak ember and the lesson hero. Redefined per theme.
 */
data class GlossoExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    /** The accent when it is text, an icon or a border: always AA on the page. */
    val accentText: Color,
    /** The streak flame. Warm, used for the daily streak and nothing else. */
    val ember: Color,
    /** The filled lesson hero and the text on it. */
    val hero: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val focus: Color,
    val scrim: Color,
    val isLight: Boolean,
)

internal val DarkExtended = GlossoExtendedColors(
    success = Color(0xFF5DD68F),
    onSuccess = Color(0xFF04210F),
    successContainer = Color(0xFF12261A),
    onSuccessContainer = Color(0xFFB4F0CB),
    warning = Color(0xFFF2B544),
    warningContainer = Color(0xFF33270C),
    onWarningContainer = Color(0xFFF6DFAE),
    danger = Color(0xFFFF7A70),
    onDanger = Color(0xFF2A0A06),
    dangerContainer = Color(0xFF2C1513),
    onDangerContainer = Color(0xFFFFCBC5),
    accentText = BrandNight.CobaltLight,
    ember = Color(0xFFFF9A3D),
    hero = BrandNight.Cobalt,
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xE6FFFFFF),
    focus = BrandNight.CobaltLight,
    scrim = Color(0x99000000),
    isLight = false,
)

internal val LightExtended = GlossoExtendedColors(
    success = Color(0xFF1C7A45),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFDDF3E5),
    onSuccessContainer = Color(0xFF0B3319),
    warning = Color(0xFF8F5B00),
    warningContainer = Color(0xFFFBECCB),
    onWarningContainer = Color(0xFF3E2800),
    danger = Color(0xFFC0342A),
    onDanger = Color(0xFFFFFFFF),
    dangerContainer = Color(0xFFFBE1DE),
    onDangerContainer = Color(0xFF5C1710),
    accentText = BrandDay.Cobalt,
    ember = Color(0xFF8F3E00),
    hero = BrandDay.Cobalt,
    onHero = Color(0xFFFFFFFF),
    onHeroMuted = Color(0xE6FFFFFF),
    focus = BrandDay.Cobalt,
    scrim = Color(0x660B0B0E),
    isLight = true,
)

internal val GlossoDarkColorScheme = darkColorScheme(
    primary = BrandNight.Cobalt,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = BrandNight.CobaltDeep,
    onPrimaryContainer = Color(0xFFD6DCFF),
    secondary = Color(0xFFB9BCD0),
    onSecondary = Color(0xFF14151F),
    secondaryContainer = Color(0xFF24253A),
    onSecondaryContainer = Color(0xFFD6DCFF),
    tertiary = Color(0xFFFF9A3D),
    onTertiary = Color(0xFF2A1400),
    background = BrandNight.Ink,
    onBackground = BrandNight.Text,
    surface = BrandNight.Surface,
    onSurface = BrandNight.Text,
    surfaceVariant = BrandNight.Raised,
    onSurfaceVariant = BrandNight.Muted,
    surfaceContainerLowest = BrandNight.Ink,
    surfaceContainerLow = Color(0xFF121216),
    surfaceContainer = BrandNight.Surface,
    surfaceContainerHigh = BrandNight.Raised,
    surfaceContainerHighest = Color(0xFF282830),
    outline = BrandNight.Line,
    outlineVariant = Color(0xFF1F1F26),
    error = Color(0xFFFF7A70),
    onError = Color(0xFF2A0A06),
    errorContainer = Color(0xFF2C1513),
    onErrorContainer = Color(0xFFFFCBC5),
    scrim = Color(0x99000000),
)

internal val GlossoLightColorScheme = lightColorScheme(
    primary = BrandDay.Cobalt,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E7FF),
    onPrimaryContainer = Color(0xFF101C66),
    secondary = Color(0xFF4A4C63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E7FF),
    onSecondaryContainer = Color(0xFF101C66),
    tertiary = Color(0xFFB85200),
    onTertiary = Color(0xFFFFFFFF),
    background = BrandDay.Paper,
    onBackground = BrandDay.Text,
    surface = BrandDay.Surface,
    onSurface = BrandDay.Text,
    surfaceVariant = BrandDay.Raised,
    onSurfaceVariant = BrandDay.Muted,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8FA),
    surfaceContainer = BrandDay.Surface,
    surfaceContainerHigh = BrandDay.Raised,
    surfaceContainerHighest = Color(0xFFE0E0E8),
    outline = BrandDay.Line,
    outlineVariant = Color(0xFFE6E6EC),
    error = Color(0xFFC0342A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE1DE),
    onErrorContainer = Color(0xFF5C1710),
    scrim = Color(0x660B0B0E),
)

internal val LocalGlossoColors = staticCompositionLocalOf { DarkExtended }

/** The active extended palette. Read [GlossoColors].success, .accentText, etc. */
val GlossoColors: GlossoExtendedColors
    @Composable @ReadOnlyComposable get() = LocalGlossoColors.current

/**
 * Color for a mastery level. Theme-aware: green for a hit, amber and coral for
 * slips, never the brand cobalt.
 */
@Composable
@ReadOnlyComposable
fun levelColor(name: String): Color = when (name) {
    "PERFECT", "MASTERED" -> GlossoColors.success
    "ALMOST" -> GlossoColors.warning
    "ROUGH" -> if (GlossoColors.isLight) Color(0xFFB4541C) else Color(0xFFFF9A5C)
    else -> GlossoColors.danger
}
