package com.glosso.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.designsystem.levelColor
import com.glosso.app.resources.*
import com.glosso.engine.model.ExerciseModel
import org.jetbrains.compose.resources.stringResource

/**
 * One exercise as a flat block. It is controlled: the parent owns whether the
 * answer has been revealed and how it went, so the session around it can score,
 * re-queue and animate. Answering calls [onAnswer] once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExerciseCard(
    exercise: ExerciseModel,
    onAnswer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    revealed: Boolean = false,
    correct: Boolean = false,
    resetKey: Any = exercise.id,
    retry: Boolean = false,
    source: ExerciseSource? = null,
    onOpenSource: ((ExerciseSource) -> Unit)? = null,
) {
    var chosen by remember(exercise.id, resetKey) { mutableStateOf<String?>(null) }
    var typed by remember(exercise.id, resetKey) { mutableStateOf("") }
    var placed by remember(exercise.id, resetKey) { mutableStateOf(emptyList<String>()) }
    // Unknown task types are self-checked: reveal the answer, then move on.
    var fallbackRevealed by remember(exercise.id, resetKey) { mutableStateOf(false) }

    // A gap that comes with a word bank is answered by choosing, not typing: a
    // beginner cannot type a script they are only starting to read.
    val wordBank = exercise.type == "fill-blank" && exercise.options.isNotEmpty()
    val isChoice = exercise.type == "multiple-choice" || exercise.type == "true-false" || wordBank
    val options = if (exercise.type == "true-false") listOf("true", "false") else exercise.options
    val allWords = remember(exercise.id) {
        exercise.prompt.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    val remaining = allWords.toMutableList().apply { placed.forEach { remove(it) } }

    // A short sideways shake on a miss, and a springy lift on a hit.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(revealed, resetKey) {
        if (revealed && !correct) {
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 320
                    -12f at 70 using LinearEasing
                    9f at 150 using LinearEasing
                    -5f at 230 using LinearEasing
                    0f at 320 using LinearEasing
                },
            )
        }
    }
    // A right answer lifts the card once and lets it settle back, so it never
    // rests wider than the screen gutter.
    val lift = remember { Animatable(1f) }
    LaunchedEffect(revealed, correct) {
        if (revealed && correct) {
            lift.animateTo(1.02f, tween(Motion.instant))
            lift.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        } else {
            lift.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = shake.value
                scaleX = lift.value
                scaleY = lift.value
            },
    ) {
    Column(Modifier.fillMaxWidth()) {
        if (source != null && onOpenSource != null) {
            SourceRef(source = source, onClick = { onOpenSource(source) })
            Spacer(Modifier.height(Space.x12))
        }
        Instruction(
            type = if (wordBank) FILL_BLANK_CHOICE else exercise.type,
            instruction = exercise.instruction,
            number = number,
            retry = retry,
        )
        Spacer(Modifier.height(Space.x12))
        AssetImage(exercise.image, contentDescription = null)
        Spacer(Modifier.height(Space.x12))

        when {
            isChoice -> {
                Text(exercise.prompt, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Space.x12))
                options.forEachIndexed { index, option ->
                    StaggeredAppear(index = index) {
                        ChoiceOption(
                            index = index,
                            // "true" and "false" are the content's values, not words
                            // for the learner: show them in the interface language.
                            label = when {
                                exercise.type != "true-false" -> option
                                option == "true" -> stringResource(Res.string.exercise_true)
                                else -> stringResource(Res.string.exercise_false)
                            },
                            chosen = chosen == option,
                            isCorrect = option == exercise.answer,
                            revealed = revealed,
                            onClick = {
                                if (!revealed) {
                                    chosen = option
                                    onAnswer(option == exercise.answer)
                                }
                            },
                        )
                    }
                }
            }

            exercise.type == "fill-blank" -> {
                Text(exercise.prompt, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Space.x12))
                // Read-only rather than disabled once checked: the learner's own
                // answer stays legible next to the verdict instead of greying out.
                val good = levelColor("MASTERED")
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    readOnly = revealed,
                    isError = revealed && !correct,
                    label = { Text(stringResource(Res.string.exercise_your_answer)) },
                    shape = MaterialTheme.shapes.small,
                    colors = if (revealed && correct) {
                        OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = good,
                            focusedBorderColor = good,
                            unfocusedLabelColor = good,
                            focusedLabelColor = good,
                        )
                    } else {
                        glossoTextFieldColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.x12))
                if (!revealed) {
                    PrimaryButton(
                        text = stringResource(Res.string.exercise_check),
                        enabled = typed.isNotBlank(),
                        onClick = { onAnswer(normalize(typed) == normalize(exercise.answer)) },
                    )
                }
            }

            exercise.type == "order" -> {
                // The drop zone is a real, boxed area with its own label and an
                // empty hint, so the pool of words below can never be read as
                // words already placed. The two zones are clearly separated.
                Text(
                    text = stringResource(Res.string.exercise_order_placed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x8))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
                    shape = RoundedCornerShape(Radius.m),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp),
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Space.x12),
                        horizontalArrangement = Arrangement.spacedBy(Space.x8),
                        verticalArrangement = Arrangement.spacedBy(Space.x8),
                    ) {
                        placed.forEach { word ->
                            WordChip(
                                text = word,
                                placed = true,
                                enabled = !revealed,
                                onClick = { if (!revealed) placed = placed - word },
                            )
                        }
                        if (placed.isEmpty()) {
                            Text(
                                stringResource(Res.string.exercise_order_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.x20))
                Text(
                    text = stringResource(Res.string.exercise_order_available),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x8))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.x8),
                    verticalArrangement = Arrangement.spacedBy(Space.x8),
                ) {
                    remaining.forEach { word ->
                        WordChip(
                            text = word,
                            enabled = !revealed,
                            onClick = { if (!revealed) placed = placed + word },
                        )
                    }
                }
                Spacer(Modifier.height(Space.x16))
                if (!revealed) {
                    PrimaryButton(
                        text = stringResource(Res.string.exercise_check),
                        enabled = placed.isNotEmpty(),
                        onClick = {
                            onAnswer(normalize(placed.joinToString(" ")) == normalize(exercise.answer))
                        },
                    )
                }
            }

            exercise.type == "match" -> {
                Text(exercise.prompt, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Space.x12))
                MatchBoard(
                    options = exercise.options,
                    resetKey = resetKey,
                    enabled = !revealed,
                    onComplete = { onAnswer(true) },
                )
            }

            // A type this build does not render natively. Never leave the card
            // blank: show the prompt, any options, and a self-check reveal.
            else -> {
                Text(exercise.prompt, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                if (exercise.options.isNotEmpty()) {
                    Spacer(Modifier.height(Space.x12))
                    exercise.options.forEach { option ->
                        Text(
                            stringResource(Res.string.label_bullet, option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Space.x2),
                        )
                    }
                }
                Spacer(Modifier.height(Space.x16))
                if (!revealed && !fallbackRevealed) {
                    PrimaryButton(
                        text = stringResource(Res.string.exercise_show_answer),
                        onClick = { fallbackRevealed = true },
                    )
                }
                if (!revealed && fallbackRevealed) {
                    Text(
                        text = stringResource(Res.string.exercise_answer, exercise.answer),
                        style = MaterialTheme.typography.bodyLarge,
                        color = GlossoColors.accentText,
                    )
                    Spacer(Modifier.height(Space.x12))
                    PrimaryButton(
                        text = stringResource(Res.string.exercise_got_it),
                        onClick = { onAnswer(true) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = revealed,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
            exit = fadeOut(tween(120)),
        ) {
            Column {
                Spacer(Modifier.height(Space.x16))
                Verdict(correct = correct, answer = exercise.answer)
            }
        }
    }
    CorrectCelebration(active = revealed && correct, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun SourceRef(source: ExerciseSource, onClick: () -> Unit) {
    val icon = when (source) {
        is ExerciseSource.Story -> Icons.Outlined.AutoStories
        is ExerciseSource.Lesson -> Icons.AutoMirrored.Outlined.MenuBook
    }
    val label = when (source) {
        is ExerciseSource.Story -> stringResource(Res.string.exercise_source_story, source.title)
        is ExerciseSource.Lesson -> stringResource(Res.string.exercise_source_lesson, source.title)
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
        shape = RoundedCornerShape(Radius.full),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.x12, vertical = Space.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = GlossoColors.accentText,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.x8))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.x8))
            Text(
                text = stringResource(Res.string.exercise_source_read_first),
                style = MaterialTheme.typography.labelMedium,
                color = GlossoColors.accentText,
                maxLines = 1,
            )
            Spacer(Modifier.width(Space.x4))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = GlossoColors.accentText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun Instruction(type: String, instruction: String?, number: Int?, retry: Boolean) {
    val (icon, defaultText) = instruction(type)
    val label = instruction?.takeIf { it.isNotBlank() } ?: defaultText
    val text = if (number != null) stringResource(Res.string.label_pair, number.toString(), label) else label
    Row(verticalAlignment = Alignment.Top) {
        // The icon sits on the first line's centre, so a label that wraps keeps
        // it aligned with where the reading starts.
        Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(Space.x8))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (retry) {
            Spacer(Modifier.width(Space.x8))
            Text(
                text = stringResource(Res.string.exercise_again),
                style = MaterialTheme.typography.labelSmall,
                color = GlossoColors.accentText,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, GlossoColors.accentText.copy(alpha = 0.5f), RoundedCornerShape(Radius.full))
                    .padding(horizontal = Space.x8, vertical = Space.x2),
            )
        }
    }
}

/** A fill-blank whose content carries a word bank; only a wording, not a content type. */
private const val FILL_BLANK_CHOICE = "fill-blank-choice"

/** Exercise types this build renders natively. Anything else uses the fallback. */
internal val SUPPORTED_EXERCISE_TYPES = setOf(
    "multiple-choice",
    "true-false",
    "fill-blank",
    "order",
    "match",
)

/**
 * Natively rendered exercise types live in [SUPPORTED_EXERCISE_TYPES]. Any other
 * type falls back to a safe self-check (prompt + options + reveal the answer);
 * the server can therefore publish new types without the app shipping a blank
 * card, and a per-item `instruction` from the content overrides this wording.
 */
@Composable
private fun instruction(type: String): Pair<ImageVector, String> = when (type) {
    "multiple-choice" -> Icons.Outlined.CheckCircle to stringResource(Res.string.exercise_choose_word)
    "true-false" -> Icons.AutoMirrored.Outlined.HelpOutline to stringResource(Res.string.exercise_true_false)
    "fill-blank" -> Icons.Outlined.Edit to stringResource(Res.string.exercise_fill_blank)
    FILL_BLANK_CHOICE -> Icons.Outlined.CheckCircle to stringResource(Res.string.exercise_fill_blank_choice)
    "order" -> Icons.AutoMirrored.Outlined.Sort to stringResource(Res.string.exercise_order)
    "match" -> Icons.AutoMirrored.Outlined.CompareArrows to stringResource(Res.string.exercise_match)
    else -> Icons.AutoMirrored.Outlined.HelpOutline to stringResource(Res.string.exercise_answer_question)
}

/**
 * One answer, lettered like a printed test (A, B, C). A hit turns its letter
 * into a check in the success tone, a miss into a cross, and the options that
 * played no part step back. The card around them carries the motion.
 */
@Composable
private fun ChoiceOption(
    index: Int,
    label: String,
    chosen: Boolean,
    isCorrect: Boolean,
    revealed: Boolean,
    onClick: () -> Unit,
) {
    val hit = revealed && isCorrect
    val miss = revealed && chosen && !isCorrect
    val good = levelColor("MASTERED")
    val bad = MaterialTheme.colorScheme.error
    val container = when {
        hit -> good.copy(alpha = 0.14f)
        miss -> bad.copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surface
    }
    val edge = when {
        hit -> good.copy(alpha = 0.7f)
        miss -> bad.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outline
    }
    val alpha by animateFloatAsState(
        targetValue = if (revealed && !hit && !miss) 0.5f else 1f,
        animationSpec = tween(Motion.base),
        label = "optionAlpha",
    )
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = !revealed,
        color = container,
        shape = RoundedCornerShape(Radius.m),
        border = BorderStroke(1.dp, edge),
        interactionSource = interaction,
        modifier = Modifier
            .padding(vertical = Space.x4)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressScale(interaction)
            .graphicsLayer { this.alpha = alpha },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.x12, vertical = Space.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            hit -> good
                            miss -> bad
                            else -> Color.Transparent
                        },
                    )
                    .border(1.dp, if (hit || miss) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    hit -> Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(Res.string.exercise_correct),
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(18.dp),
                    )
                    miss -> Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.exercise_incorrect),
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(18.dp),
                    )
                    else -> Text(
                        text = ('A' + index).toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Space.x12))
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun WordChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    placed: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (placed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            1.dp,
            if (placed) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = Space.x16, vertical = Space.x12),
                style = MaterialTheme.typography.bodyLarge,
                color = if (placed) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun Verdict(correct: Boolean, answer: String) {
    val data by ProgressStore.state.collectAsState()
    val good = levelColor("MASTERED")
    val praise = when {
        data.streak >= 5 -> stringResource(Res.string.exercise_praise_streak, data.streak)
        else -> stringResource(Res.string.exercise_correct)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (correct) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                contentDescription = null,
                tint = if (correct) good else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Space.x8))
            Text(
                text = if (correct) praise else stringResource(Res.string.exercise_answer, answer),
                style = MaterialTheme.typography.bodyLarge,
                color = if (correct) good else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!correct) {
            Spacer(Modifier.height(Space.x4))
            Text(
                text = stringResource(Res.string.exercise_returns),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Case- and punctuation-insensitive comparison so "Station." matches "station"
 * and an authored order answer like "Come home at six, please." matches the
 * tap-built "Come home at six please" (the prompt has no punctuation).
 */
internal fun normalize(value: String): String =
    value.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
