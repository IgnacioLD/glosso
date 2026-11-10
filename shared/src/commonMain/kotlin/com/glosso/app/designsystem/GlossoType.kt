package com.glosso.app.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource

/**
 * Type families bundled with the app (all SIL Open Font License, see
 * licenses/fonts/). Variable fonts: one file per family covers every weight,
 * and each [Font] pins its own instance through the variation axes.
 *
 * - Bricolage Grotesque: display, titles and big numbers. A grotesque with ink
 *   traps and a lively rhythm at large optical sizes: confident and human, the
 *   Glosso voice. Set tight and heavy, never tracked out.
 * - Plus Jakarta Sans: the whole UI. Geometric, open counters, excellent at
 *   small sizes and with full Latin for es/fr/de.
 * - JetBrains Mono: IPA and tabular numbers.
 */
@OptIn(ExperimentalTextApi::class)
object GlossoFonts {
    /**
     * One instance of a variable font. The weight axis has to be set by hand:
     * without it Android draws the file's default instance whatever the
     * requested weight.
     */
    @Composable
    private fun variable(res: FontResource, weight: FontWeight) = Font(
        res,
        weight,
        FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

    @Composable
    private fun display(weight: FontWeight, opticalSize: Float) = Font(
        Res.font.bricolage_grotesque,
        weight,
        FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.Setting("opsz", opticalSize),
        ),
    )

    /**
     * The four families. Compose Multiplatform loads a font resource inside
     * composition, so [GlossoTheme] builds them once and publishes them.
     */
    @Composable
    internal fun rememberFamilies(): GlossoFontFamilies {
        // Headlines: the large optical size tightens spacing and sharpens the traps.
        val display = listOf(
            display(FontWeight.SemiBold, 48f),
            display(FontWeight.Bold, 48f),
            display(FontWeight.ExtraBold, 72f),
        )
        // Titles and numbers at card size: the text optical size stays open.
        val displayText = listOf(
            display(FontWeight.SemiBold, 20f),
            display(FontWeight.Bold, 20f),
            display(FontWeight.ExtraBold, 24f),
        )
        val ui = listOf(
            variable(Res.font.plus_jakarta_sans, FontWeight.Normal),
            variable(Res.font.plus_jakarta_sans, FontWeight.Medium),
            variable(Res.font.plus_jakarta_sans, FontWeight.SemiBold),
            variable(Res.font.plus_jakarta_sans, FontWeight.Bold),
            variable(Res.font.plus_jakarta_sans, FontWeight.ExtraBold),
        )
        val mono = listOf(
            variable(Res.font.jetbrains_mono, FontWeight.Normal),
            variable(Res.font.jetbrains_mono, FontWeight.Medium),
        )
        return remember(display, displayText, ui, mono) {
            GlossoFontFamilies(
                display = FontFamily(display),
                displayText = FontFamily(displayText),
                ui = FontFamily(ui),
                mono = FontFamily(mono),
            )
        }
    }

    private val current: GlossoFontFamilies
        @Composable get() = LocalGlossoFonts.current ?: rememberFamilies()

    val Display: FontFamily @Composable get() = current.display
    val DisplayText: FontFamily @Composable get() = current.displayText
    val Ui: FontFamily @Composable get() = current.ui
    val Mono: FontFamily @Composable get() = current.mono
}

@Immutable
class GlossoFontFamilies internal constructor(
    val display: FontFamily,
    val displayText: FontFamily,
    val ui: FontFamily,
    val mono: FontFamily,
)

/** Set by [GlossoTheme]; null outside it, where [GlossoFonts] loads its own. */
internal val LocalGlossoFonts = staticCompositionLocalOf<GlossoFontFamilies?> { null }

/**
 * Styles that do not map onto a Material slot: the hero number and the IPA line.
 * Read them as [GlossoType.NumberXL] / [GlossoType.MonoIPA].
 */
object GlossoType {
    /** Big tabular number for scores, percentages and streaks. */
    val NumberXL: TextStyle @Composable get() = TextStyle(
        fontFamily = GlossoFonts.Display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.2).sp,
        textAlign = TextAlign.Start,
        fontFeatureSettings = "tnum",
    )

    /** IPA transcription. Always monospaced. */
    val MonoIPA: TextStyle @Composable get() = TextStyle(
        fontFamily = GlossoFonts.Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    /** A figure in a row (a percentage, minutes): bold UI face, tabular digits. */
    val Figure: TextStyle @Composable get() = TextStyle(
        fontFamily = GlossoFonts.Ui,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )

    /** A small kicker above a title: sentence case, bold, never tracked out. */
    val Eyebrow: TextStyle @Composable get() = TextStyle(
        fontFamily = GlossoFonts.Ui,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    )
}

/**
 * Material typography mapped to the Glosso scale. Bricolage Grotesque, heavy
 * and tightly set, for display, headlines and card titles; Plus Jakarta Sans
 * for everything UI. Negative tracking grows with size so big titles read as
 * one confident shape.
 */
internal fun glossoTypography(fonts: GlossoFontFamilies): Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = fonts.display,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 46.sp,
            lineHeight = 48.sp,
            letterSpacing = (-1.6).sp,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = fonts.display,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 38.sp,
            lineHeight = 42.sp,
            letterSpacing = (-1.2).sp,
        ),
        displaySmall = base.displaySmall.copy(
            fontFamily = fonts.display,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.8).sp,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = fonts.display,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.7).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = fonts.display,
            fontWeight = FontWeight.Bold,
            fontSize = 25.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.5).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = fonts.displayText,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 27.sp,
            letterSpacing = (-0.3).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = fonts.displayText,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 25.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            letterSpacing = (-0.1).sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            lineHeight = 25.sp,
            letterSpacing = 0.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            letterSpacing = 0.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = fonts.ui,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.4.sp,
        ),
    )
}
