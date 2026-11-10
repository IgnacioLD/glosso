package com.glosso.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glosso.app.audio.PieceState
import com.glosso.app.billing.AccessController
import com.glosso.app.billing.ProductAccess
import com.glosso.app.billing.SubscriptionStatus
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.WordModel
import org.jetbrains.compose.resources.stringResource

@Composable
fun StoriesScreen(
    vm: StoriesViewModel = storiesViewModel(),
    subscription: SubscriptionStatus = SubscriptionStatus.NONE,
    productAccess: ProductAccess = ProductAccess.Unknown,
    onShowPaywall: (String?) -> Unit = {},
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    // One gate for the whole list: each story carries its own CEFR level and the
    // controller answers per item, so no row re-derives the frontier.
    val access = remember(subscription, productAccess) { AccessController(subscription, productAccess = productAccess) }

    val story = state.reading
    if (story != null) {
        val piece by vm.pieceState.collectAsState()
        StoryReader(
            story = story,
            exercises = state.exercises[story.id].orEmpty(),
            themeLabels = state.themeLabels,
            playback = piece,
            vocabulary = state.vocabulary,
            word = state.word,
            vm = vm,
            onBack = { vm.closeStory() },
        )
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(title = stringResource(Res.string.stories_title), onBack = onBack)

        when {
            state.loading && state.stories.isEmpty() ->
                LoadingState(Modifier.weight(1f))

            state.error != null && state.stories.isEmpty() ->
                ErrorState(onRetry = { vm.load() }, modifier = Modifier.weight(1f), detail = state.error)

            state.stories.isEmpty() ->
                EmptyState(
                    title = stringResource(Res.string.stories_empty_title),
                    message = stringResource(Res.string.stories_empty_message),
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.AutoStories,
                )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = listBottomPadding()),
            ) {
                itemsIndexed(state.stories, key = { _, it -> it.id }) { index, item ->
                    val locked = access.isLocked(item)
                    val open = { if (locked) onShowPaywall(item.level) else vm.openStory(item) }
                    StaggeredAppear(index = index) {
                        if (index == 0) {
                            StoryRow(story = item, labels = state.themeLabels, locked = locked, onClick = open)
                        } else {
                            StoryCompactRow(story = item, labels = state.themeLabels, locked = locked, onClick = open)
                        }
                    }
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun StoryRow(
    story: StoryModel,
    labels: Map<String, String>,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.x16),
    ) {
        AssetImage(story.image, contentDescription = story.title)
        Spacer(Modifier.height(Space.x12))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.label_pair, story.level, themeLabel(story.theme, labels)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locked) {
                Spacer(Modifier.width(Space.x8))
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = stringResource(Res.string.lessons_unlock_with_pro),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.x8))
        Text(
            text = story.title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        story.paragraphs.firstOrNull()?.let {
            Spacer(Modifier.height(Space.x8))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Every story after the first: a thumbnail beside its level, theme and title. */
@Composable
private fun StoryCompactRow(
    story: StoryModel,
    labels: Map<String, String>,
    locked: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = Space.x16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp)) {
            AssetImage(story.image, contentDescription = story.title)
        }
        Spacer(Modifier.width(Space.x16))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.label_pair, story.level, themeLabel(story.theme, labels)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (locked) {
                    Spacer(Modifier.width(Space.x8))
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = stringResource(Res.string.lessons_unlock_with_pro),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(Space.x4))
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StoryReader(
    story: StoryModel,
    exercises: List<ExerciseModel>,
    themeLabels: Map<String, String>,
    playback: PieceState,
    vocabulary: Map<String, WordModel>,
    word: WordModel?,
    vm: StoriesViewModel,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val autoScroll = rememberReadingAutoScroll(scrollState)
    // The furthest point scrolled to, for how much of the story was read.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (scrolled, max) ->
            if (max in 1 until Int.MAX_VALUE) vm.onReadProgress(readFraction(scrolled, max))
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SubHeader(title = stringResource(Res.string.stories_reader_title), onBack = onBack)
            // How far through the story the reader is, following the scroll.
            ProgressTrack(
                progress = { readFraction(scrollState.value, scrollState.maxValue) },
                modifier = Modifier.padding(horizontal = Space.gutter),
            )
            Spacer(Modifier.height(Space.x8))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEdgeFade(scrollState)
                    .verticalScroll(scrollState)
                    .onGloballyPositioned { autoScroll.onContentPositioned(it) }
                    .navigationBarsPadding(),
            ) {
                Column(Modifier.padding(horizontal = Space.gutter)) {
                    Spacer(Modifier.height(Space.x8))
                    AssetImage(story.image, contentDescription = story.title)
                    Spacer(Modifier.height(Space.x20))
                    Text(
                        text = stringResource(
                            Res.string.label_pair,
                            story.level,
                            themeLabel(story.theme, themeLabels),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.x8))
                    Text(
                        text = story.title,
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.x20))
                    ReadingPiece(
                        title = null,
                        image = null,
                        paragraphs = story.paragraphs,
                        playback = playback,
                        onTogglePlay = { vm.toggleParagraphPlayback() },
                        onPlayParagraph = { index -> vm.playParagraphFrom(index) },
                        onSetSlow = { slow -> vm.setSlowParagraphs(slow) },
                        vocabulary = vocabulary,
                        onWordTap = { vm.showWord(it) },
                        autoScroll = autoScroll,
                    )
                }
                if (exercises.isNotEmpty()) {
                    Spacer(Modifier.height(Space.x24))
                    Hairline()
                    Spacer(Modifier.height(Space.x24))
                    SectionLabel(
                        stringResource(Res.string.stories_practice),
                        Modifier.padding(horizontal = Space.gutter),
                    )
                    Spacer(Modifier.height(Space.x20))
                    ExerciseRunner(exercises = exercises, embedded = true)
                }
                Spacer(Modifier.height(Space.x40))
            }
        }
        if (word != null) {
            TrackScreen("word")
            WordDetailSheet(
                word = word,
                onPlay = { vm.playWordAudio(word) },
                onDismiss = { vm.showWord(null) },
            )
        }
    }
}
