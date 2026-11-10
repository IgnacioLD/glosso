package com.glosso.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glosso.app.audio.PieceState
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.model.WordModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A story or article read as one piece: one big play/pause control that chains
 * every paragraph on its own, the paragraph in play highlighted, and the position
 * in the piece. Tapping a paragraph plays from there; a paragraph with no clip is
 * not tappable and, when the whole piece has no audio, the block says so instead
 * of offering a control that would do nothing.
 *
 * [vocabulary] is the piece's theme lexicon, if any: its words are drawn as links
 * in the text and [onWordTap] hands back the word behind the tapped token, so a
 * reading gets the same IPA, meaning and audio the practice step shows.
 */
@Composable
internal fun ReadingPiece(
    title: String?,
    image: String?,
    paragraphs: List<String>,
    playback: PieceState,
    onTogglePlay: () -> Unit,
    onPlayParagraph: (Int) -> Unit,
    onSetSlow: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    vocabulary: Map<String, WordModel> = emptyMap(),
    onWordTap: (WordModel) -> Unit = {},
    autoScroll: ReadingAutoScroll? = null,
) {
    val annotated = paragraphs.map { paragraph ->
        vocabularyParagraph(paragraph, vocabulary, onWordTap)
    }
    val hasVocabulary = remember(paragraphs, vocabulary) {
        vocabulary.isNotEmpty() && paragraphs.any { StoryVocabulary.spans(it, vocabulary).isNotEmpty() }
    }
    Column(modifier.fillMaxWidth()) {
        if (image != null) {
            AssetImage(image, contentDescription = title)
            Spacer(Modifier.height(Space.x16))
        }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x12))
        }
        if (playback.hasAudio) {
            PiecePlaybackBar(playback, onTogglePlay, onSetSlow)
            Spacer(Modifier.height(Space.x8))
        } else {
            NoAudioNote()
            Spacer(Modifier.height(Space.x8))
        }
        if (hasVocabulary) {
            VocabularyHint()
            Spacer(Modifier.height(Space.x4))
        }
        paragraphs.forEachIndexed { index, _ ->
            ParagraphRow(
                text = annotated[index],
                current = playback.currentParagraph == index,
                playing = playback.isPlaying && playback.currentParagraph == index,
                hasAudio = index in playback.queue,
                onPlay = { onPlayParagraph(index) },
                modifier = if (autoScroll != null) {
                    Modifier.onGloballyPositioned { autoScroll.onItemPositioned(index, it) }
                } else {
                    Modifier
                },
            )
        }
        AutoScrollToPlaying(autoScroll, playback.currentParagraph)
    }
}

/**
 * The paragraph with its vocabulary words turned into links. Nothing to tap
 * (no vocabulary, or none of it in this paragraph) returns the plain text
 * untouched, so a story without a lexicon renders exactly as before.
 */
@Composable
private fun vocabularyParagraph(
    paragraph: String,
    vocabulary: Map<String, WordModel>,
    onWordTap: (WordModel) -> Unit,
): AnnotatedString {
    val accent = GlossoColors.accentText
    val tap = rememberUpdatedState(onWordTap)
    return remember(paragraph, vocabulary, accent) {
        val spans = StoryVocabulary.spans(paragraph, vocabulary)
        if (spans.isEmpty()) return@remember AnnotatedString(paragraph)
        buildAnnotatedString {
            append(paragraph)
            spans.forEach { span ->
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "word",
                        styles = TextLinkStyles(
                            style = SpanStyle(color = accent, fontWeight = FontWeight.Bold),
                        ),
                        linkInteractionListener = LinkInteractionListener { tap.value(span.word) },
                    ),
                    start = span.start,
                    end = span.end,
                )
            }
        }
    }
}

@Composable
private fun VocabularyHint() {
    Text(
        text = stringResource(Res.string.stories_vocabulary_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The one control that plays a whole piece, shared by readings and dialogues. */
@Composable
internal fun PiecePlaybackBar(
    state: PieceState,
    onTogglePlay: () -> Unit,
    onSetSlow: (Boolean) -> Unit,
    positionRes: StringResource = Res.string.audio_paragraph_of,
) {
    val toggleDescription = stringResource(
        if (state.isPlaying) Res.string.audio_pause else Res.string.audio_play_piece,
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Space.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(Sizes.touchPrimary)
                    .semantics { contentDescription = toggleDescription },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(Sizes.icon),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    state.isPlaying -> Icon(
                        Icons.Outlined.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.icon),
                    )
                    else -> Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(Sizes.icon),
                    )
                }
            }
            Spacer(Modifier.width(Space.x12))
            // The speed toggle shares the title row, so the status line below gets
            // the full width and never breaks into a narrow column of words.
            Column(Modifier.weight(1f)) {
                val shownPosition =
                    if (state.finished) state.total.coerceAtLeast(1) else state.position.coerceAtLeast(1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(positionRes, shownPosition, state.total),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.hasSlow) {
                        Spacer(Modifier.width(Space.x8))
                        SpeedToggle(slow = state.slow, onToggle = { onSetSlow(!state.slow) })
                    }
                }
                Text(
                    text = stringResource(
                        when {
                            state.isLoading -> Res.string.audio_loading
                            state.isPlaying -> Res.string.audio_playing
                            state.isPaused -> Res.string.audio_paused
                            state.finished -> Res.string.audio_finished
                            else -> Res.string.audio_listen_all
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The playback speed as a compact pill: a 48 dp touch target around a 32 dp
 * visual, gold only while the slow voice is on.
 */
@Composable
private fun SpeedToggle(slow: Boolean, onToggle: () -> Unit) {
    val accent = GlossoColors.accentText
    Box(
        modifier = Modifier
            .heightIn(min = Sizes.touch)
            .clip(RoundedCornerShape(Radius.full))
            .clickable(role = Role.Switch, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .border(
                    1.dp,
                    if (slow) accent else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(Radius.full),
                )
                .background(
                    if (slow) accent.copy(alpha = 0.14f) else Color.Transparent,
                    RoundedCornerShape(Radius.full),
                )
                .padding(horizontal = Space.x12, vertical = Space.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = if (slow) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.x4))
            Text(
                text = stringResource(if (slow) Res.string.practice_slow else Res.string.practice_normal),
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 0.2.sp,
                color = if (slow) accent else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun NoAudioNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.m))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Space.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.VolumeOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Sizes.iconSmall),
        )
        Spacer(Modifier.width(Space.x8))
        Text(
            text = stringResource(Res.string.audio_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ParagraphRow(
    text: AnnotatedString,
    current: Boolean,
    playing: Boolean,
    hasAudio: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val playLabel = stringResource(Res.string.audio_play_from)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (hasAudio) Sizes.touch else 0.dp)
            .clip(RoundedCornerShape(Radius.s))
            .background(if (current) accent.copy(alpha = 0.12f) else Color.Transparent)
            .then(
                if (hasAudio) {
                    // onClickLabel keeps the paragraph itself readable by TalkBack
                    // and adds the action, instead of replacing the text.
                    Modifier.clickable(onClickLabel = playLabel, onClick = onPlay)
                } else {
                    Modifier
                },
            )
            .padding(vertical = Space.x8, horizontal = Space.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(if (current) 24.dp else 0.dp)
                .clip(RoundedCornerShape(Radius.full))
                .background(if (current) accent else Color.Transparent),
        )
        Spacer(Modifier.width(Space.x8))
        Column(Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (current && playing) {
            Spacer(Modifier.width(Space.x8))
            PlayingBars(modifier = Modifier.height(16.dp))
        }
    }
}
