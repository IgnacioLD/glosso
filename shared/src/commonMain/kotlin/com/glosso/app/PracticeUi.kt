package com.glosso.app

import com.glosso.app.platform.currentPlatformContext
import com.glosso.app.platform.rememberConnectivity
import com.glosso.app.platform.rememberHaptics
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.GlossoType
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.designsystem.levelColor
import com.glosso.app.resources.*
import com.glosso.engine.model.MasteryLevel
import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PhonemeErrorModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.ProsodyBonusModel
import com.glosso.engine.model.WordFeedbackModel
import com.glosso.engine.phonetics.PhoneticLoop
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The pronunciation loop, shared by free practice and the lesson player. One
 * card with the phrase, the reference controls, a recorder with a live waveform
 * and a result that names the sound to fix.
 */

/** Levels that warrant hearing the reference again, right where the score is. */
internal val RETRY_LEVELS = setOf(MasteryLevel.ROUGH, MasteryLevel.NOT_YET)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DrillCard(
    text: String,
    ipa: String,
    translation: String?,
    focus: List<String>,
    hasAudio: Boolean = false,
    onPlay: (() -> Unit)? = null,
) {
    // The phrase is the page, not a box on it: the focus sounds as quiet chips,
    // the sentence in the serif at display size, the IPA under it and the
    // meaning on request. A round speaker sits beside the chips so hearing the
    // reference is one obvious tap.
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    val playDescription = stringResource(Res.string.audio_play_reference)
    val tappable = hasAudio && onPlay != null
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        if (focus.isNotEmpty() || tappable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Space.x8),
                    verticalArrangement = Arrangement.spacedBy(Space.x8),
                ) {
                    focus.forEach { GlossoTag(it) }
                }
                if (tappable) {
                    Spacer(Modifier.width(Space.x12))
                    Box(
                        Modifier
                            .size(Sizes.touch)
                            .pressScale(interaction, pressed = 0.9f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClickLabel = playDescription,
                                onClick = onPlay,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = playDescription,
                            tint = GlossoColors.accentText,
                            modifier = Modifier.size(Sizes.icon),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.x20))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = if (tappable) {
                Modifier.clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = playDescription,
                    onClick = onPlay,
                )
            } else {
                Modifier
            },
        )
        if (showPhonetics && ipa.isNotBlank()) {
            Spacer(Modifier.height(Space.x12))
            Text(
                text = ipa,
                style = GlossoType.MonoIPA,
                color = GlossoColors.accentText,
            )
        }
        if (translation != null) {
            Spacer(Modifier.height(Space.x8))
            TranslationReveal(translation)
        }
    }
}

/**
 * The meaning of the phrase, hidden until the learner asks for it. With the
 * translation always under the phrase the eye lands on the known language and
 * stops working on the new one, so it starts hidden and the learner reveals it
 * with one quiet control. One shared preference drives every card and the
 * Settings switch, so the inline reveal and the switch never disagree.
 */
@Composable
internal fun TranslationReveal(translation: String?) {
    if (translation == null) return
    Column {
        TranslationLine(translation)
        TranslationToggle()
    }
}

/**
 * The meaning of the phrase, shown only while the learner has asked to see
 * translations; nothing at all when the content carries none.
 */
@Composable
internal fun TranslationLine(translation: String?) {
    if (translation == null) return
    val visible by TranslationPreferences.visible.collectAsState()
    if (!visible) return
    Text(
        text = translation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The quiet control that reveals or hides the meaning. Its label is the spoken
 * description too, so the action is never a guess.
 */
@Composable
internal fun TranslationToggle() {
    val visible by TranslationPreferences.visible.collectAsState()
    val context = currentPlatformContext()
    TextButton(
        onClick = { TranslationPreferences.setVisible(context, !visible) },
        contentPadding = PaddingValues(horizontal = Space.x8, vertical = Space.x4),
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.Translate,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Space.x8))
        Text(
            text = stringResource(
                if (visible) Res.string.translation_hide else Res.string.translation_show,
            ),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Three bars that dance while audio plays, instead of a static icon. */
@Composable
internal fun PlayingBars(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    if (reduce) {
        Row(
            modifier = modifier.height(18.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "playing")
    val a by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "bar0")
    val b by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "bar1")
    val c by transition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(620), RepeatMode.Reverse), label = "bar2")
    Row(
        modifier = modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(a, b, c).forEach { h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((18 * h).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** One-tap replay of the reference, shown right where a rough score lands. */
@Composable
internal fun HearAgainButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SecondaryButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(Sizes.iconSmall),
        )
        Spacer(Modifier.width(Space.x8))
        Text(stringResource(Res.string.practice_hear_again), style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResultCard(
    feedback: PronunciationFeedback,
    onPracticeError: ((PhonemeErrorModel) -> Unit)? = null,
    focus: FocusPractice? = null,
    practiceUnavailable: Boolean = false,
    previousScore: Int? = null,
) {
    val accent = levelColor(feedback.level.name)
    val sounds = PhoneticLoop.breakdown(feedback.alignment)
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    val haptics = rememberHaptics()
    LaunchedEffect(feedback) { haptics.press() }
    Column(Modifier.fillMaxWidth()) {
        Hairline(gold = true)
        Spacer(Modifier.height(Space.x24))
        // The verdict: a ring that sweeps to the score, the level in its color
        // and the one-line reading of what happened.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = feedback.score, color = accent, size = 124.dp)
            Spacer(Modifier.width(Space.x20))
            Column(Modifier.weight(1f)) {
                Text(
                    levelTitle(feedback.level),
                    style = MaterialTheme.typography.headlineSmall,
                    color = accent,
                )
                Spacer(Modifier.height(Space.x8))
                Text(
                    levelMessage(feedback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        TakeProgress(current = feedback.score, previous = previousScore)

        if (feedback.words.isNotEmpty()) {
            Spacer(Modifier.height(Space.x24))
            // Each word carries its level as an underline, so the sentence stays
            // readable in one color and the slips still jump out. The letters that
            // did not land are marked in red inside the word on top of that.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.x12),
                verticalArrangement = Arrangement.spacedBy(Space.x8),
            ) {
                feedback.words.forEach { word ->
                    val color = levelColor(word.level.name)
                    Text(
                        text = wordText(word),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .drawBehind {
                                val h = 3.dp.toPx()
                                drawRoundRect(
                                    color = color,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                                    size = androidx.compose.ui.geometry.Size(size.width, h),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h, h),
                                )
                            }
                            .padding(bottom = Space.x4),
                    )
                }
            }
        }

        feedback.prosody?.let { prosody ->
            Spacer(Modifier.height(Space.x32))
            ProsodySection(prosody)
        }

        if (showPhonetics && sounds.isNotEmpty()) {
            Spacer(Modifier.height(Space.x32))
            SectionLabel(stringResource(Res.string.practice_sounds))
            Spacer(Modifier.height(Space.x12))
            sounds.take(MAX_SOUNDS).chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.x8),
                ) {
                    row.forEach { sound -> SoundTile(sound, Modifier.weight(1f)) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(Space.x8))
            }
        }

        if (feedback.errors.isNotEmpty()) {
            Spacer(Modifier.height(Space.x24))
            DiagnosisCards(
                errors = distinctDiagnoses(feedback.errors),
                words = feedback.words,
                onPractice = onPracticeError,
                unavailable = practiceUnavailable,
            )
        }

        if (showPhonetics && focus != null && focus.scores.size >= 2) {
            Spacer(Modifier.height(Space.x16))
            FocusTrend(focus)
        }
    }
}

/**
 * This take against the previous one on the same item. Nothing is shown on the
 * first take, so the card never invents a comparison. Rising is named plainly
 * ("Improving", with the delta); falling encourages without blaming, because a
 * worse repeat is part of learning, not a punishment.
 */
@Composable
private fun TakeProgress(current: Int, previous: Int?) {
    if (previous == null) return
    val trend = takeTrend(current, previous)
    val up = trend == TakeTrend.UP
    val icon = when (trend) {
        TakeTrend.UP -> Icons.AutoMirrored.Filled.TrendingUp
        TakeTrend.DOWN -> Icons.Outlined.Refresh
        TakeTrend.SAME -> Icons.Outlined.GraphicEq
    }
    val color = if (up) GlossoColors.success else MaterialTheme.colorScheme.onSurfaceVariant
    val detail = when (trend) {
        TakeTrend.UP -> stringResource(Res.string.practice_progress_up_detail, current - previous)
        TakeTrend.DOWN -> stringResource(Res.string.practice_progress_down_detail, previous - current)
        TakeTrend.SAME -> null
    }
    Spacer(Modifier.height(Space.x16))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(Sizes.iconSmall),
        )
        Spacer(Modifier.width(Space.x8))
        Column {
            Text(
                text = when (trend) {
                    TakeTrend.UP -> stringResource(Res.string.practice_progress_up)
                    TakeTrend.DOWN -> stringResource(Res.string.practice_progress_down)
                    TakeTrend.SAME -> stringResource(Res.string.practice_progress_same)
                },
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
            if (detail != null) {
                Spacer(Modifier.height(Space.x2))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "Ritmo y entonacion": the extra points for following the reference's rhythm,
 * pauses, stress and intonation. Shown apart from the score and said to be
 * extra, so nobody reads it as part of the sound score; one tip for the
 * weakest dimension keeps it actionable.
 */
@Composable
private fun ProsodySection(prosody: ProsodyBonusModel) {
    SectionLabel(stringResource(Res.string.prosody_title))
    Spacer(Modifier.height(Space.x12))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.m),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "+${prosody.points}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = GlossoColors.accentText,
                )
                Spacer(Modifier.width(Space.x12))
                Text(
                    text = stringResource(Res.string.prosody_points, prosody.maxPoints),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Space.x4))
            Text(
                text = stringResource(Res.string.prosody_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x16))
            prosodyDimensions(prosody).forEach { (dimension, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(dimension.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(0.4f),
                    )
                    ProgressLine(fraction = value, modifier = Modifier.weight(0.6f))
                }
                Spacer(Modifier.height(Space.x8))
            }
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(prosodyFocus(prosody)?.tip ?: Res.string.prosody_tip_good),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** One sound of a take as a small tile: the symbol, its number and a hairline meter. */
@Composable
private fun SoundTile(sound: PhoneticLoop.SoundScore, modifier: Modifier = Modifier) {
    val color = accuracyColor(sound.score)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.m),
        border = cardBorder(),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = Space.x12, vertical = Space.x12)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "/${sound.phoneme}/",
                    style = GlossoType.MonoIPA,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${sound.score}",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(Space.x8))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(Radius.full))
                    .background(MaterialTheme.colorScheme.outline),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((sound.score / 100f).coerceIn(0f, 1f))
                        .height(3.dp)
                        .clip(RoundedCornerShape(Radius.full))
                        .background(color),
                )
            }
        }
    }
}

/**
 * One sentence word with the letters that did not land marked, so the learner
 * sees the exact spot inside the word and not only the whole-word level. The mark
 * is colour plus an underline, so it reads even without colour. Falls back to the
 * whole word when the engine could not align characters.
 */
@Composable
private fun wordText(word: WordFeedbackModel): AnnotatedString {
    if (word.letters.isEmpty()) return AnnotatedString(word.text)
    val base = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        word.letters.forEach { letter ->
            val mark = when (letter.status) {
                MatchStatusModel.MISSED -> GlossoColors.danger
                MatchStatusModel.CLOSE -> GlossoColors.warning
                MatchStatusModel.PERFECT -> null
            }
            withStyle(
                SpanStyle(
                    color = mark ?: base,
                    textDecoration = if (mark != null) TextDecoration.Underline else null,
                ),
            ) { append(letter.char) }
        }
    }
}

/**
 * The fixes for the diagnosed sounds, worst first. The heading carries the real
 * count, so two or more slips are never described in the singular, and every
 * sound keeps its own diagnosis, minimal pair and one-tap practice button.
 */
@Composable
private fun DiagnosisCards(
    errors: List<PhonemeErrorModel>,
    words: List<WordFeedbackModel>,
    onPractice: ((PhonemeErrorModel) -> Unit)?,
    unavailable: Boolean,
) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    Text(
        text = diagnosisHeading(errors, showPhonetics),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(Space.x12))
    errors.forEachIndexed { index, error ->
        DiagnosisCard(
            error = error,
            word = words.getOrNull(error.wordIndex),
            onPractice = onPractice?.let { { it(error) } },
            unavailable = unavailable,
        )
        if (index != errors.lastIndex) Spacer(Modifier.height(Space.x12))
    }
}

@Composable
private fun diagnosisHeading(errors: List<PhonemeErrorModel>, showPhonetics: Boolean): String {
    // The heading counts sounds, not cards: two different slips on /θ/ are
    // two fixes for one sound.
    val sounds = errors.map { it.phoneme }.distinct()
    return when {
        sounds.size == 1 && showPhonetics -> stringResource(Res.string.practice_work_on, sounds.first())
        sounds.size == 1 -> stringResource(Res.string.practice_work_on_plain)
        showPhonetics -> stringResource(
            Res.string.practice_work_on_many,
            sounds.size,
            sounds.joinToString(" ") { "/$it/" },
        )
        else -> pluralStringResource(Res.plurals.practice_work_on_count_plain, sounds.size, sounds.size)
    }
}

/**
 * One card per distinct diagnosis. The scorer reports a slip per occurrence, so
 * the same confusion in "think" and "third" arrives twice; the learner needs the
 * fix once. Order (worst first) is kept.
 */
internal fun distinctDiagnoses(errors: List<PhonemeErrorModel>): List<PhonemeErrorModel> =
    errors.distinctBy { Triple(it.phoneme, it.actual, it.ruleId) }

/**
 * The recommended fix for one diagnosed sound. It names the word the slip was
 * heard in, describes the sound in plain language with the IPA only as a support
 * line, and shows the wrong and the right example side by side so the contrast is
 * seen, not just told.
 */
@Composable
private fun DiagnosisCard(
    error: PhonemeErrorModel,
    word: WordFeedbackModel?,
    onPractice: (() -> Unit)?,
    unavailable: Boolean = false,
) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    val rule = MaterialTheme.colorScheme.primary
    val guide = SoundGuide.text(error.phoneme)
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(Radius.m),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .drawBehind {
                    drawRect(rule, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                }
                .padding(start = Space.x20, end = Space.x16, top = Space.x16, bottom = Space.x16),
        ) {
            if (word != null && word.text.isNotBlank()) {
                Text(
                    text = stringResource(Res.string.practice_error_in_word, word.text),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(Space.x8))
            }
            Text(
                text = when {
                    showPhonetics && error.actual != null ->
                        stringResource(Res.string.practice_diagnosis_sub, error.phoneme, error.actual ?: "")
                    showPhonetics -> stringResource(Res.string.practice_diagnosis_dropped, error.phoneme)
                    error.actual != null -> stringResource(Res.string.practice_diagnosis_sub_plain)
                    else -> stringResource(Res.string.practice_diagnosis_dropped_plain)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Space.x4))
            Text(
                text = guide ?: if (showPhonetics) {
                    defaultErrorTitle(error)
                } else {
                    stringResource(Res.string.error_phoneme_dropped_plain)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val pair = soundPair(error)
            if (pair.rightWord != null || pair.wrongWord != null) {
                Spacer(Modifier.height(Space.x16))
                ExampleContrast(pair)
            }
            if (onPractice != null) {
                Spacer(Modifier.height(Space.x16))
                SecondaryButton(
                    onClick = onPractice,
                    enabled = !unavailable,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.iconSmall),
                    )
                    Spacer(Modifier.width(Space.x8))
                    Text(
                        if (showPhonetics) {
                            stringResource(Res.string.practice_practise_sound, error.phoneme)
                        } else {
                            stringResource(Res.string.practice_practise_sound_plain)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (unavailable) {
                    Spacer(Modifier.height(Space.x8))
                    Text(
                        stringResource(Res.string.practice_drill_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * The wrong and the right example, side by side and well contrasted. Colour is a
 * state here, never the only signal: each column carries an icon and a label, and
 * the part that differs is underlined inside the word, so the contrast survives
 * without colour. Each word plays its clip when the catalog holds it.
 */
@Composable
private fun ExampleContrast(pair: SoundPair, modifier: Modifier = Modifier) {
    val paths by ExampleAudio.paths.collectAsState()
    val playing by ExampleAudio.playing.collectAsState()
    val words = listOfNotNull(pair.wrongWord, pair.rightWord)
    LaunchedEffect(words) { ExampleAudio.resolve(words) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.x12),
    ) {
        pair.wrongWord?.let { wrong ->
            ContrastColumn(
                label = stringResource(Res.string.practice_example_wrong),
                word = wrong,
                other = pair.rightWord.orEmpty(),
                color = GlossoColors.danger,
                icon = Icons.Outlined.Close,
                playable = paths[ExampleAudio.cacheKey(wrong)] != null,
                playing = playing == ExampleAudio.cacheKey(wrong),
                onClick = { ExampleAudio.play(wrong) },
                modifier = Modifier.weight(1f),
            )
        }
        pair.rightWord?.let { right ->
            ContrastColumn(
                label = stringResource(Res.string.practice_example_right),
                word = right,
                other = pair.wrongWord.orEmpty(),
                color = GlossoColors.success,
                icon = Icons.Outlined.Check,
                playable = paths[ExampleAudio.cacheKey(right)] != null,
                playing = playing == ExampleAudio.cacheKey(right),
                onClick = { ExampleAudio.play(right) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ContrastColumn(
    label: String,
    word: String,
    other: String,
    color: Color,
    icon: ImageVector,
    playable: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = stringResource(Res.string.practice_tap_example)
    Column(
        modifier
            .then(if (playable) Modifier.clickable(onClickLabel = hint, onClick = onClick) else Modifier)
            .padding(vertical = Space.x4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(Sizes.iconSmall),
            )
            Spacer(Modifier.width(Space.x4))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        Spacer(Modifier.height(Space.x4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = contrastWord(word, other, color),
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
            if (playable) {
                Spacer(Modifier.width(Space.x8))
                if (playing) {
                    PlayingBars(modifier = Modifier.height(16.dp))
                } else {
                    Icon(
                        Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(Sizes.iconSmall),
                    )
                }
            }
        }
    }
}

/** The example word with the letters that differ from the other one underlined. */
@Composable
private fun contrastWord(word: String, other: String, color: Color): AnnotatedString {
    if (other.isBlank()) return AnnotatedString(word)
    val differing = differingIndices(word, other)
    return buildAnnotatedString {
        word.forEachIndexed { index, char ->
            withStyle(
                if (index in differing) {
                    SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
                } else {
                    SpanStyle(color = color.copy(alpha = 0.75f))
                },
            ) { append(char) }
        }
    }
}

/**
 * The letters of [word] that carry the contrast with [other]. The shared start
 * and end are stripped first (so a repeated letter, like the shared "l" in
 * pull/pool, is not mistaken for the contrast), then the rest is aligned with a
 * longest-common-subsequence walk: only the letters that are not part of the
 * common subsequence differ. A word that is a subsequence of the other (an
 * insertion, as in rue/roue) has nothing to drop, so the character where the
 * insertion lands is marked instead of leaving the word bare. Empty when the
 * words match.
 */
internal fun differingIndices(word: String, other: String): Set<Int> {
    val a = word.lowercase()
    val b = other.lowercase()
    if (a == b) return emptySet()
    var prefix = 0
    while (prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < a.length - prefix && suffix < b.length - prefix &&
        a[a.length - 1 - suffix] == b[b.length - 1 - suffix]
    ) {
        suffix++
    }
    val aMid = a.substring(prefix, a.length - suffix)
    val bMid = b.substring(prefix, b.length - suffix)
    val m = aMid.length
    val n = bMid.length
    val lcs = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            lcs[i][j] = if (aMid[i] == bMid[j]) lcs[i + 1][j + 1] + 1
            else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val longest = lcs[0][0]
    val differing = linkedSetOf<Int>()
    var i = 0
    var j = 0
    var matched = 0
    while (i < m && j < n) {
        if (aMid[i] == bMid[j]) {
            i++; j++; matched++
            continue
        }
        // Keep the leftmost alignment that still reaches the longest common
        // subsequence, so shared letters stay shared instead of being marked.
        var next = -1
        for (k in j until n) {
            if (bMid[k] == aMid[i] && matched + 1 + lcs[i + 1][k + 1] == longest) {
                next = k
                break
            }
        }
        if (next >= 0) {
            j = next
            continue
        }
        differing += prefix + i
        i++
    }
    while (i < m) {
        differing += prefix + i
        i++
    }
    if (differing.isEmpty()) {
        // Pure insertion into the other word: point at where it lands.
        differing += prefix.coerceIn(0, (a.length - 1).coerceAtLeast(0))
    }
    return differing
}

/**
 * The minimal-pair words that train a diagnosed sound, each tappable to hear it
 * alone. A word whose clip the catalog does not hold is shown as plain text
 * rather than a dead tap, and the hint appears only when there is something to
 * play, so the affordance never lies.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExampleWords(words: List<String>, modifier: Modifier = Modifier) {
    if (words.isEmpty()) return
    val paths by ExampleAudio.paths.collectAsState()
    val playing by ExampleAudio.playing.collectAsState()
    LaunchedEffect(words) { ExampleAudio.resolve(words) }
    val playable = words.any { paths[ExampleAudio.cacheKey(it)] != null }
    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.x8),
            verticalArrangement = Arrangement.spacedBy(Space.x8),
        ) {
            words.forEach { word ->
                val key = ExampleAudio.cacheKey(word)
                if (paths[key] != null) {
                    ExampleWordChip(
                        word = word,
                        playing = playing == key,
                        onClick = { ExampleAudio.play(word) },
                    )
                } else {
                    Text(
                        text = word,
                        style = GlossoType.MonoIPA,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(vertical = Space.x8),
                    )
                }
            }
        }
        if (playable) {
            Spacer(Modifier.height(Space.x8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(Sizes.iconSmall),
                )
                Spacer(Modifier.width(Space.x8))
                Text(
                    text = stringResource(Res.string.practice_tap_example),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ExampleWordChip(word: String, playing: Boolean, onClick: () -> Unit) {
    val label = stringResource(Res.string.practice_tap_example)
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(Radius.full),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f),
        ),
        modifier = Modifier.clickable(onClickLabel = label, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.x12, vertical = Space.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = word,
                style = GlossoType.MonoIPA,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(Space.x8))
            if (playing) {
                PlayingBars(modifier = Modifier.height(16.dp))
            } else {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(Sizes.iconSmall),
                )
            }
        }
    }
}

/**
 * The way out when a take cannot be scored offline because the on-device model
 * is not installed: name what happened and offer the one action that fixes it.
 * With no connection there is nothing to download yet, so it says to connect
 * instead of showing a button that would fail.
 */
@Composable
internal fun OnDeviceModelOffer(onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val pack by LocalAppServices.current.onDevice.state.collectAsState()
    val online by rememberConnectivity().online.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.DownloadForOffline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.icon),
                )
                Spacer(Modifier.width(Space.x12))
                Text(
                    text = stringResource(Res.string.scoring_no_model_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(Res.string.scoring_no_model_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x16))
            when {
                pack.downloading -> {
                    ProgressLine(pack.progress)
                    Spacer(Modifier.height(Space.x8))
                    Text(
                        text = stringResource(Res.string.settings_downloading, (pack.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlossoColors.accentText,
                    )
                }

                !online -> Text(
                    text = stringResource(Res.string.scoring_no_model_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> PrimaryButton(
                    text = stringResource(Res.string.scoring_download_model, onDeviceModelSize()),
                    onClick = onDownload,
                )
            }
            pack.error?.let {
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlossoColors.danger,
                )
            }
        }
    }
}

/**
 * Shown when nothing usable was heard. It never carries a number or a failing
 * verdict: the attempt simply could not be evaluated, and repeating it is free.
 */
@Composable
internal fun NotEvaluatedCard() {
    // Not a score and not an error: a calm card that says the system could not
    // judge this take, in the neutral tone, so it never reads as a bad mark.
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Space.x20), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MicOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.iconSmall),
                )
            }
            Spacer(Modifier.width(Space.x16))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.score_not_evaluated_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.x4))
                Text(
                    text = stringResource(Res.string.score_not_evaluated_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The generic "how it went wrong" line, when the rule has no curated title. */
@Composable
private fun defaultErrorTitle(error: PhonemeErrorModel): String {
    val actual = error.actual
    return if (actual != null) {
        stringResource(Res.string.error_phoneme_note, error.phoneme, actual)
    } else {
        stringResource(Res.string.error_phoneme_dropped, error.phoneme)
    }
}

/** The trend of the sound being trained: 33 -> 60 -> 71, and whether it moved. */
@Composable
private fun FocusTrend(focus: FocusPractice) {
    val trend = focus.scores.joinToString("  ->  ")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (focus.improved) Icons.AutoMirrored.Filled.TrendingUp else Icons.Outlined.GraphicEq,
            contentDescription = null,
            tint = if (focus.improved) GlossoColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Sizes.iconSmall),
        )
        Spacer(Modifier.width(Space.x8))
        Column {
            Text(
                text = if (focus.improved) {
                    stringResource(Res.string.practice_improved, focus.phoneme)
                } else {
                    stringResource(Res.string.practice_trend, focus.phoneme)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (focus.improved) GlossoColors.success else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x2))
            Text(
                text = trend,
                style = GlossoType.MonoIPA,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_SOUNDS = 6

@Composable
internal fun levelTitle(level: MasteryLevel): String = when (level) {
    MasteryLevel.PERFECT -> stringResource(Res.string.level_perfect)
    MasteryLevel.MASTERED -> stringResource(Res.string.level_mastered)
    MasteryLevel.ALMOST -> stringResource(Res.string.level_almost)
    MasteryLevel.ROUGH -> stringResource(Res.string.level_rough)
    MasteryLevel.NOT_YET -> stringResource(Res.string.level_not_yet)
}

@Composable
internal fun levelMessage(feedback: PronunciationFeedback): String {
    feedback.feedback?.takeIf { it.isNotBlank() }?.let { return it }
    return when (feedback.level) {
        MasteryLevel.PERFECT -> stringResource(Res.string.feedback_perfect)
        // A good take may carry a stray slip, so it must not claim every sound landed.
        MasteryLevel.MASTERED -> stringResource(Res.string.feedback_mastered)
        MasteryLevel.ALMOST -> stringResource(Res.string.feedback_almost)
        MasteryLevel.ROUGH -> stringResource(Res.string.feedback_rough)
        MasteryLevel.NOT_YET -> stringResource(Res.string.feedback_not_yet)
    }
}
