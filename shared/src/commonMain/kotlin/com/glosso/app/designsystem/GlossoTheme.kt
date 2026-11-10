package com.glosso.app.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.glosso.app.platform.rememberReduceMotion

private val GlossoShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.s),
    medium = RoundedCornerShape(Radius.m),
    large = RoundedCornerShape(Radius.l),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/**
 * The one theme of the app. Follows the system light/dark setting; dark is the
 * brand default. Also publishes the extended color roles and the reduce-motion
 * flag.
 */
@Composable
fun GlossoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val colorScheme = if (darkTheme) GlossoDarkColorScheme else GlossoLightColorScheme
    val extended = if (darkTheme) DarkExtended else LightExtended
    val fonts = GlossoFonts.rememberFamilies()
    val typography = remember(fonts) { glossoTypography(fonts) }

    CompositionLocalProvider(
        LocalGlossoColors provides extended,
        LocalReduceMotion provides reduceMotion,
        LocalGlossoFonts provides fonts,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = GlossoShapes,
            content = content,
        )
    }
}

/**
 * Re-themes [content] to sit on the filled cobalt hero: text and icons turn
 * white, the primary action inverts to a white pill with cobalt text, and
 * tracks and tags fade to translucent white. Components inside keep reading
 * the normal roles, so nothing in them needs a hero variant.
 */
@Composable
fun OnHero(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val ext = GlossoColors
    val white = ext.onHero
    val scheme = base.copy(
        primary = white,
        onPrimary = ext.hero,
        surface = ext.hero,
        onSurface = white,
        onSurfaceVariant = ext.onHeroMuted,
        surfaceVariant = white.copy(alpha = 0.12f),
        background = ext.hero,
        onBackground = white,
        outline = white.copy(alpha = 0.26f),
        outlineVariant = white.copy(alpha = 0.16f),
        primaryContainer = white.copy(alpha = 0.16f),
        onPrimaryContainer = white,
    )
    CompositionLocalProvider(
        LocalGlossoColors provides ext.copy(accentText = white, focus = white),
    ) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
    }
}
