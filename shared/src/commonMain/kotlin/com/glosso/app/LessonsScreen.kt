package com.glosso.app

import com.glosso.app.download.DownloadIds
import com.glosso.app.download.LocalDownloader
import com.glosso.app.platform.currentUiLanguage
import com.glosso.app.platform.isoDayOfWeek
import com.glosso.app.platform.narrowWeekdayNames
import com.glosso.app.platform.rememberConnectivity
import com.glosso.app.platform.todayEpochDay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.GlossoType
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.download.DownloadStatus
import com.glosso.app.resources.*
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.ScoringConfig
import com.glosso.engine.model.WordModel
import com.glosso.engine.model.displayPhonetic
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun LessonsScreen(
    vm: LessonViewModel = lessonViewModel(),
) {
    val state by vm.state.collectAsState()
    val progress by ProgressStore.state.collectAsState()
    val offline by OfflineStore.state.collectAsState()
    val active = state.active
    var showLevelPicker by remember { mutableStateOf(false) }
    var downloadSheet by remember { mutableStateOf<LessonModel?>(null) }

    // Re-read the catalog whenever the screen returns to the foreground, so
    // lessons and levels published since the last launch appear without an
    // update - and no stale cache can hide them.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showLevelPicker) {
        TrackScreen("level_picker")
        LevelPickerSheet(
            current = state.level,
            levels = state.levels.ifEmpty { listOf("ALL") },
            onSelect = {
                vm.setLevel(it)
                showLevelPicker = false
            },
            onDismiss = { showLevelPicker = false },
        )
    }

    downloadSheet?.let { lesson ->
        TrackScreen("download_sheet")
        DownloadSheet(
            lesson = lesson,
            offline = offline,
            state = state,
            vm = vm,
            onDismiss = { downloadSheet = null },
        )
    }

    val listState = rememberLazyListState()
    val collapse = rememberCollapse(listState)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopHeader(
                title = stringResource(Res.string.lessons_title),
                collapse = { collapse.value },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StreakChip(days = progress.shownDailyStreak())
                        Spacer(Modifier.width(Space.x12))
                        LevelButton(
                            level = state.level,
                            pickable = state.levels.size > 1,
                            onClick = { showLevelPicker = true },
                        )
                    }
                },
            )
            when {
                state.loading && state.lessons.isEmpty() ->
                    LoadingState(Modifier.weight(1f))

                state.error != null && state.lessons.isEmpty() ->
                    ErrorState(
                        onRetry = { vm.refresh() },
                        modifier = Modifier.weight(1f),
                        detail = state.error,
                    )

                state.lessons.isEmpty() ->
                    EmptyState(
                        title = if (state.level == "ALL") stringResource(Res.string.lessons_empty_all)
                        else stringResource(Res.string.lessons_empty_level, state.level),
                        message = stringResource(Res.string.lessons_empty_message),
                        modifier = Modifier.weight(1f),
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                    )

                else -> {
                    // The resume target lives here, once,
                    // shared by the big hero and the compact bar so the two can
                    // never disagree on what "Continue" does.
                    val today = state.lessons.firstOrNull { it.id == state.resumeLessonId }
                        ?: CourseOrder.nextLesson(state.lessons, state.themeOrder, progress.finishedLessons)
                    val resumeStep = state.resumeStep
                        .takeIf { today != null && today.id == state.resumeLessonId && it != LessonStep.SUMMARY }
                    // "Start here" is only true of the very first lesson: once the
                    // learner has moved past it, the hero names what comes next.
                    val first = CourseOrder.firstLesson(state.lessons, state.themeOrder)
                    val heroKicker = when {
                        resumeStep != null -> Res.string.lessons_continue_kicker
                        today != null && today.id != first?.id -> Res.string.lessons_next_kicker
                        else -> Res.string.lessons_start_kicker
                    }
                    val openToday: () -> Unit = {
                        when {
                            resumeStep != null -> vm.resume()
                            else -> today?.let { vm.openLesson(it) }
                        }
                    }

                    // Measure the real layout, not the item index: item 0 is
                    // exactly the hero card, so the bar shows precisely once that
                    // card has left by the top and hides the moment any of it is
                    // back. The bar lives outside the list, so its appearance
                    // shrinks the viewport instead of covering a row, and no
                    // hidden row can receive a touch. totalItemsCount is 0 before
                    // the first measure, when visibleItemsInfo is still empty:
                    // without that guard the bar would be born visible and
                    // animate straight out, a flash on every cold start.
                    val heroOffScreen by remember(today?.id) {
                        derivedStateOf {
                            val info = listState.layoutInfo
                            val measured = info.totalItemsCount > 0
                            val hero = info.visibleItemsInfo.firstOrNull { it.index == 0 }
                            today != null && measured && (hero == null || hero.offset + hero.size <= 0)
                        }
                    }

                    Column(Modifier.fillMaxWidth().weight(1f)) {
                        if (today != null) {
                            ContinueBar(
                                visible = heroOffScreen,
                                lesson = today,
                                kicker = heroKicker,
                                step = resumeStep,
                                onOpen = openToday,
                            )
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f).scrollEdgeFade(listState),
                            contentPadding = PaddingValues(
                                start = Space.gutter,
                                end = Space.gutter,
                                bottom = Space.x40,
                            ),
                        ) {
                            if (today != null) {
                                item(key = "today") {
                                    Spacer(Modifier.height(Space.x20))
                                    TodayHeroCard(
                                        lesson = today,
                                        kicker = heroKicker,
                                        step = resumeStep,
                                        onOpen = openToday,
                                    )
                                }
                            }
                            val byTheme = state.lessons.groupBy { it.theme }
                            CourseOrder.blockOrder(state.lessons, state.themeOrder)
                                .forEach { theme ->
                                    val lessons = byTheme[theme].orEmpty().sortedWith(CourseOrder.lessonOrder)
                                    item(key = "section:$theme") {
                                        UnitHeader(
                                            title = themeLabel(theme, state.themeLabels),
                                            done = lessons.count { it.id in progress.finishedLessons },
                                            total = lessons.size,
                                        )
                                    }
                                    itemsIndexed(lessons, key = { _, lesson -> lesson.id }) { index, lesson ->
                                        val finished = lesson.id in progress.finishedLessons
                                        val inProgressStep = state.resumeStep
                                            .takeIf { lesson.id == state.resumeLessonId && !finished }
                                        StaggeredAppear(index = index) {
                                            PathRow(
                                                first = index == 0,
                                                last = index == lessons.lastIndex,
                                                finished = finished,
                                                stepFraction = inProgressStep?.let { stepFraction(lessonSteps(lesson), it) },
                                            ) {
                                                LessonRow(
                                                    lesson = lesson,
                                                    labels = state.themeLabels,
                                                    finished = finished,
                                                    step = inProgressStep,
                                                    offline = offline,
                                                    onClick = { vm.openLesson(lesson) },
                                                    onDownload = { downloadSheet = lesson },
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }

        if (active != null) {
            // A full-surface guard under the player: pointer events that miss the
            // player's own controls stop here instead of reaching the lesson list
            // behind. It is a sibling, not an ancestor, so the player's buttons
            // and scrolling still get every event first.
            Box(Modifier.fillMaxSize().consumePointerEvents())
            LessonPlayer(
                vm = vm,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            )
        }
    }
}

/**
 * The lesson to continue, kept one tap away once the hero scrolls off: a single
 * low row, title and its action, held above the list so no row is ever covered
 * and no hidden row can be tapped. It never shows while the hero is on screen,
 * so the same action is never offered twice. Grows and fades in discreetly, and
 * instantly when reduce motion is on.
 */
@Composable
private fun ContinueBar(
    visible: Boolean,
    lesson: LessonModel,
    kicker: StringResource,
    step: LessonStep?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduce) {
            EnterTransition.None
        } else {
            fadeIn(tween(Motion.fast)) +
                expandVertically(tween(Motion.fast, easing = Motion.decelerate), expandFrom = Alignment.Top)
        },
        exit = if (reduce) {
            ExitTransition.None
        } else {
            fadeOut(tween(Motion.fast)) +
                shrinkVertically(tween(Motion.fast, easing = Motion.accelerate), shrinkTowards = Alignment.Top)
        },
        modifier = modifier,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.gutter, vertical = Space.x4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(stringResource(kicker))
                        Text(
                            text = lesson.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(Space.x12))
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(Radius.full),
                        contentPadding = PaddingValues(horizontal = Space.x20),
                        modifier = Modifier.heightIn(min = Sizes.touch),
                    ) {
                        Text(
                            text = stringResource(
                                if (step != null) Res.string.lessons_continue else Res.string.lessons_start,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Hairline()
            }
        }
    }
}

/**
 * The one thing to do today. A brand-gradient hero, not another list row: the
 * lesson to continue (or the first one), how far it got and one clear action.
 */
@Composable
private fun TodayHeroCard(
    lesson: LessonModel,
    kicker: StringResource,
    step: LessonStep?,
    onOpen: () -> Unit,
) {
    LessonHeroSurface(
        kicker = stringResource(kicker),
        tags = { GlossoTag(baseLabel(lesson)) },
    ) {
        LessonHeroTitle(lesson.title, lesson.objective)
        if (step != null) {
            Spacer(Modifier.height(Space.x20))
            LessonStepProgress(lessonSteps(lesson), step)
        }
        Spacer(Modifier.height(Space.x24))
        PrimaryButton(
            text = stringResource(if (step != null) Res.string.lessons_continue else Res.string.lessons_start),
            onClick = onOpen,
        )
    }
}

/**
 * A unit of the course (a theme) as a chapter heading: its name in brass and
 * how many of its lessons are done, so progress reads per unit, not only per row.
 */
@Composable
private fun UnitHeader(title: String, done: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(top = Space.x32, bottom = Space.x8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(title, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(Res.string.practice_counter, done, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Space.x8))
        ProgressLine(if (total == 0) 0f else done / total.toFloat())
    }
}

/**
 * One stop on the course path: a rail runs down the left edge through every
 * lesson of a unit, and each lesson sits on a node that says its state - a
 * filled brass seal when done, a ring filling up while in progress, an open
 * circle still to do.
 */
@Composable
private fun PathRow(
    first: Boolean,
    last: Boolean,
    finished: Boolean,
    stepFraction: Float?,
    content: @Composable () -> Unit,
) {
    val rail = MaterialTheme.colorScheme.outline
    val nodeTop = 30.dp
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(36.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = 14.dp.toPx()
                    val y = nodeTop.toPx()
                    val top = if (first) y else 0f
                    val bottom = if (last) y else size.height
                    if (bottom > top) {
                        drawLine(rail, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5.dp.toPx())
                    }
                },
        ) {
            Box(
                Modifier
                    .padding(top = nodeTop - 14.dp)
                    .size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                PathNode(finished = finished, stepFraction = stepFraction)
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun PathNode(finished: Boolean, stepFraction: Float?) {
    val bg = MaterialTheme.colorScheme.background
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    when {
        finished -> Box(
            Modifier.size(28.dp).clip(CircleShape).background(primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(Res.string.lessons_finished),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }

        stepFraction != null -> {
            val fraction = stepFraction
            Canvas(Modifier.size(28.dp)) {
                drawCircle(bg)
                val w = 3.dp.toPx()
                val arc = Size(size.width - w, size.height - w)
                drawArc(outline, -90f, 360f, false, Offset(w / 2, w / 2), arc, style = Stroke(w))
                drawArc(primary, -90f, 360f * fraction, false, Offset(w / 2, w / 2), arc, style = Stroke(w, cap = StrokeCap.Round))
                drawCircle(primary, radius = 4.dp.toPx())
            }
        }

        else -> Box(
            Modifier.size(14.dp).clip(CircleShape).background(bg).border(1.5.dp, outline, CircleShape),
        )
    }
}

/** The current week, seven honest dots. The rhythm of effort, at a glance. */
@Composable
internal fun WeekDots(data: ProgressData) {
    val today = todayEpochDay()
    val monday = today - (isoDayOfWeek(today) - 1)
    val initials = narrowWeekdayNames()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Row(
        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (i in 0 until 7) {
            val date = monday + i
            val done = (data.days[date.toString()] ?: 0) > 0
            val isToday = date == today
            val future = date > today
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                future -> MaterialTheme.colorScheme.surfaceVariant
                                done -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            },
                        )
                        .then(
                            if (isToday) {
                                Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(Res.string.lessons_day_practiced),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(Space.x4))
                Text(
                    text = initials[i],
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isToday) GlossoColors.accentText
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }
}

@Composable
private fun LessonRow(
    lesson: LessonModel,
    labels: Map<String, String>,
    finished: Boolean,
    step: LessonStep?,
    offline: OfflineState,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.m))
            .clickable(onClick = onClick)
            .padding(vertical = Space.x16, horizontal = Space.x4),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BaseTag(baseLabel(lesson))
                Spacer(Modifier.width(Space.x12))
                Text(
                    // The unit header above already names the theme.
                    text = lesson.level,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Space.x8))
            // The title line owns the download control, so the icon lines up with
            // the title instead of floating in the middle of the row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = lesson.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (LocalAppConfig.current.offlineDownloads) {
                    Spacer(Modifier.width(Space.x8))
                    DownloadButton(lessonId = lesson.id, offline = offline, onClick = onDownload)
                }
            }
            if (lesson.objective.isNotBlank()) {
                Spacer(Modifier.height(Space.x4))
                Text(
                    text = lesson.objective,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The download state at a glance: empty, in progress, or held on device. */
@Composable
private fun DownloadButton(lessonId: String, offline: OfflineState, onClick: () -> Unit) {
    val downloading = offline.downloadingId == lessonId
    val downloaded = lessonId in offline.downloadedIds
    IconButton(onClick = onClick) {
        when {
            downloading -> CircularProgressIndicator(
                progress = { offline.progress },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )

            downloaded -> Icon(
                Icons.Outlined.CloudDone,
                contentDescription = stringResource(Res.string.lessons_downloaded),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )

            else -> Icon(
                Icons.Outlined.DownloadForOffline,
                contentDescription = stringResource(Res.string.lessons_download),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A small, quiet tag naming the base a lesson opens with. */
@Composable
private fun BaseTag(label: String) {
    GlossoTag(label)
}

/**
 * Swallows every pointer event that reaches this surface. Used as a guard layer
 * between an overlay (the lesson player, the first-run welcome) and the screen
 * behind it: without it, a tap or drag on an empty area of the overlay - the
 * header, a gap between lines, the padding around the action bar - falls through
 * to whatever row sits behind, opening another lesson by accident. Kept as a sibling under the overlay so the overlay's own buttons and
 * scrolling are never starved of events.
 */
internal fun Modifier.consumePointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

@Composable
private fun LessonPlayer(vm: LessonViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val attempt by vm.attempt.collectAsState()
    val reduce = LocalReduceMotion.current
    val lesson = state.active ?: return
    // A lesson whose content could not be loaded: the player shows one honest
    // notice with a way out instead of a blank base and a dead button. While it
    // is still resolving, it breathes rather than showing an empty base.
    val unavailable = state.lessonError != null
    val opening = state.opening && !unavailable

    Column(modifier = modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.x4, end = Space.gutter, top = Space.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeLesson() }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.lessons_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                lesson.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = Space.x4),
            )
            if (state.offlineActive) {
                Icon(
                    Icons.Outlined.CloudDone,
                    contentDescription = stringResource(Res.string.lessons_offline_badge),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (!unavailable) {
            Spacer(Modifier.height(Space.x8))
            LessonStepProgress(state.steps, state.step, Modifier.padding(horizontal = Space.gutter))
        }
        Spacer(Modifier.height(Space.x16))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (opening) {
                LoadingState(Modifier.fillMaxSize())
                return@Column
            }
            if (unavailable) {
                LessonUnavailable(
                    title = if (state.lessonOffline) {
                        stringResource(Res.string.lesson_unavailable_offline_title)
                    } else {
                        stringResource(Res.string.lesson_unavailable_title)
                    },
                    // One coherent message from the view model: offline names what
                    // to do (connect or open a downloaded lesson); otherwise the
                    // load failure, with the download offer below.
                    message = state.lessonError.orEmpty(),
                    offline = state.lessonOffline,
                    lessonId = lesson.id,
                    downloadError = state.error,
                    onRetry = { vm.retryActiveLesson() },
                    onDownload = { vm.downloadActiveLesson() },
                )
                return@Column
            }
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (reduce) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        val forward = targetState.ordinal > initialState.ordinal
                        val dir = if (forward) 1 else -1
                        (
                            slideInHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            ) { width -> dir * width } +
                                fadeIn(tween(Motion.base, easing = Motion.decelerate))
                            ) togetherWith (
                            slideOutHorizontally(
                                animationSpec = tween(Motion.base, easing = Motion.accelerate),
                            ) { width -> -dir * width / 4 } +
                                fadeOut(tween(Motion.fast))
                            )
                    }
                },
                label = "lessonStep",
            ) { step ->
                when (step) {
                    LessonStep.CONTEXT -> ContextStep(state.active?.notes.orEmpty())
                    LessonStep.BASE -> BaseStep(vm, state)
                    LessonStep.EXERCISES -> ExercisesStep(vm, state)
                    LessonStep.REVIEW, LessonStep.SPEAK -> SpeakStep(vm, state, step)
                    LessonStep.DRILLS -> DrillsStep(vm, state)
                    LessonStep.SUMMARY -> SummaryStep(state, onDebugReviewDue = { vm.debugMakeReviewDue() })
                }
            }
            TransientPhaseIndicator(
                text = stepName(state.step),
                phaseKey = state.step,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = Space.x8),
            )
        }

        LessonActionBar(
            step = state.step,
            nextEnabled = state.baseReady,
            onNext = { vm.nextStep() },
            onClose = { vm.closeLesson() },
        )
    }
}

/**
 * The lesson player's one honest dead end: the content could not be loaded
 * (usually offline, for a lesson that was not downloaded). It never leaves a
 * blank base with a disabled button; it says what happened and offers the way
 * out - retry, or download the lesson once there is a connection.
 */
@Composable
private fun LessonUnavailable(
    title: String,
    message: String,
    offline: Boolean,
    lessonId: String,
    downloadError: String?,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
) {
    val offlineState by OfflineStore.state.collectAsState()
    val downloading = offlineState.downloadingId == lessonId
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.x32, vertical = Space.x24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(Space.x16))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(Space.x8))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // A failed download started from here reports back through the same
        // state the catalog uses: without this the offer would look like it did
        // nothing.
        downloadError?.let {
            Spacer(Modifier.height(Space.x12))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = GlossoColors.danger,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Space.x32))
        if (downloading) {
            ProgressLine(offlineState.progress, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.x12))
            Text(
                text = stringResource(Res.string.lessons_downloading, (offlineState.progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = GlossoColors.accentText,
            )
        } else {
            PrimaryButton(
                text = stringResource(Res.string.retry),
                onClick = onRetry,
                modifier = Modifier.width(220.dp),
            )
            if (!offline && LocalAppConfig.current.offlineDownloads) {
                Spacer(Modifier.height(Space.x4))
                QuietTextButton(
                    text = stringResource(Res.string.lesson_download_now),
                    onClick = onDownload,
                )
            }
        }
    }
}

@Composable
private fun LessonActionBar(
    step: LessonStep,
    nextEnabled: Boolean,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    // The context, the base and the summary carry the step action here. Exercises and drills
    // own their own explicit continue button (nothing advances on its own), so
    // no bar button sits in the way there.
    val action: (@Composable () -> Unit)? = when (step) {
        LessonStep.CONTEXT -> {
            { PrimaryButton(stringResource(Res.string.lessons_context_next), onNext) }
        }
        LessonStep.BASE -> {
            { PrimaryButton(stringResource(Res.string.lessons_practise_read), onNext, enabled = nextEnabled) }
        }
        LessonStep.SUMMARY -> {
            { PrimaryButton(stringResource(Res.string.lessons_back_to_lessons), onClose) }
        }
        LessonStep.REVIEW, LessonStep.EXERCISES, LessonStep.SPEAK, LessonStep.DRILLS -> null
    }
    if (action == null) return

    BottomActionBar { action() }
}

@Composable
private fun BaseStep(vm: LessonViewModel, state: LessonState) {
    val scrollState = rememberScrollState()
    val autoScroll = rememberReadingAutoScroll(scrollState)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .scrollEdgeFade(scrollState)
            .padding(horizontal = Space.gutter)
            .verticalScroll(scrollState)
            .onGloballyPositioned { autoScroll.onContentPositioned(it) },
    ) {
        state.active?.objective?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x24))
        }

        val piece by vm.pieceState.collectAsState()
        state.dialogue?.let { dialogue ->
            AutoScrollToPlaying(autoScroll, piece.currentParagraph)
            AssetImage(dialogue.image, contentDescription = dialogue.title)
            Spacer(Modifier.height(Space.x16))
            Text(dialogue.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.x12))
            if (piece.hasAudio) {
                PiecePlaybackBar(
                    state = piece,
                    onTogglePlay = { vm.toggleBasePlayback() },
                    onSetSlow = { slow -> vm.setSlowBase(slow) },
                    positionRes = Res.string.audio_line_of,
                )
            } else {
                NoAudioNote()
            }
            Spacer(Modifier.height(Space.x8))
            // The meanings of the dialogue are hidden by default too; one quiet
            // control reveals (or hides) all of them, instead of a button per
            // turn. Nothing is shown when the content carries no translation.
            val uiLanguage = currentUiLanguage()
            if (dialogue.turns.any { it.translations.translationFor(uiLanguage) != null }) {
                TranslationToggle()
                Spacer(Modifier.height(Space.x4))
            }
            val firstSpeaker = dialogue.turns.firstOrNull()?.speaker
            dialogue.turns.forEachIndexed { index, turn ->
                DialogueTurn(
                    alignEnd = turn.speaker != firstSpeaker,
                    speaker = turn.speaker,
                    text = turn.text,
                    ipa = turn.displayPhonetic,
                    translation = uiTranslation(turn.translations),
                    current = piece.currentParagraph == index,
                    playing = piece.isPlaying && piece.currentParagraph == index,
                    hasAudio = index in piece.queue,
                    onPlay = { vm.playBaseFrom(index) },
                    modifier = Modifier.onGloballyPositioned { autoScroll.onItemPositioned(index, it) },
                )
                Spacer(Modifier.height(Space.x12))
            }
        }
        state.story?.let {
            ReadingPiece(
                title = it.title,
                image = it.image,
                paragraphs = it.paragraphs,
                playback = piece,
                onTogglePlay = { vm.toggleBasePlayback() },
                onPlayParagraph = { i -> vm.playBaseFrom(i) },
                onSetSlow = { slow -> vm.setSlowBase(slow) },
                autoScroll = autoScroll,
            )
        }
        state.article?.let {
            ReadingPiece(
                title = it.title,
                image = it.image,
                paragraphs = it.paragraphs,
                playback = piece,
                onTogglePlay = { vm.toggleBasePlayback() },
                onPlayParagraph = { i -> vm.playBaseFrom(i) },
                onSetSlow = { slow -> vm.setSlowBase(slow) },
                autoScroll = autoScroll,
            )
        }

        if (state.words.isNotEmpty()) {
            Spacer(Modifier.height(Space.x24))
            SectionLabel(stringResource(Res.string.lessons_words))
            Spacer(Modifier.height(Space.x12))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.x20),
            ) {
                state.words.forEach { word ->
                    VocabularyWord(word = word, onPlay = { vm.playWordAudio(word) })
                }
            }
        }
        Spacer(Modifier.height(Space.x24))
    }
}

/**
 * One word of a lesson's vocabulary strip: the lemma and, when phonetics are on,
 * its IPA underneath in the same quiet tone used everywhere else.
 */
@Composable
private fun VocabularyWord(word: WordModel, onPlay: () -> Unit) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    val hasAudio = word.audio.isNotEmpty()
    val ipa = word.displayPhonetic
    Column(
        modifier = Modifier
            .heightIn(min = Sizes.touch)
            .then(
                if (hasAudio) {
                    Modifier.clickable(
                        onClickLabel = stringResource(Res.string.practice_listen),
                        onClick = onPlay,
                    )
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = word.lemma,
            style = MaterialTheme.typography.bodyLarge,
            color = if (hasAudio) GlossoColors.accentText
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showPhonetics && ipa.isNotBlank()) {
            Spacer(Modifier.height(Space.x2))
            Text(
                text = ipa,
                style = GlossoType.MonoIPA,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One line of the dialogue as a speech bubble: the first speaker on the left in
 * ink, the other on the right on the brass wash, like the conversation it is.
 * The line being played lifts with a brass edge and the dancing bars.
 */
@Composable
internal fun DialogueTurn(
    alignEnd: Boolean,
    speaker: String,
    text: String,
    ipa: String,
    translation: String?,
    current: Boolean,
    playing: Boolean,
    hasAudio: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    val playLabel = stringResource(Res.string.audio_play_from)
    val corner = Radius.l
    val shape = if (alignEnd) {
        RoundedCornerShape(topStart = corner, topEnd = Radius.xs, bottomStart = corner, bottomEnd = corner)
    } else {
        RoundedCornerShape(topStart = Radius.xs, topEnd = corner, bottomStart = corner, bottomEnd = corner)
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth(),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = if (alignEnd) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = shape,
            border = BorderStroke(
                1.5.dp,
                if (current) GlossoColors.accentText else Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .pressScale(interaction)
                .then(
                    if (hasAudio) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = playLabel,
                            onClick = onPlay,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(Modifier.padding(horizontal = Space.x16, vertical = Space.x12)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = speaker,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current) GlossoColors.accentText else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (current && playing) PlayingBars(modifier = Modifier.height(14.dp))
                }
                Spacer(Modifier.height(Space.x4))
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (showPhonetics && ipa.isNotBlank()) {
                    Spacer(Modifier.height(Space.x4))
                    Text(
                        text = ipa,
                        style = GlossoType.MonoIPA,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (translation != null) {
                    Spacer(Modifier.height(Space.x4))
                    TranslationLine(translation)
                }
            }
        }
    }
}

@Composable
private fun ExercisesStep(vm: LessonViewModel, state: LessonState) {
    Box(Modifier.fillMaxSize()) {
        if (state.exercises.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.lessons_no_exercises),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ExerciseRunner(
                exercises = state.exercises,
                modifier = Modifier.fillMaxSize(),
                onComplete = { vm.nextStep() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrillsStep(vm: LessonViewModel, state: LessonState) {
    val attempt by vm.attempt.collectAsState()
    val level = vm.level.collectAsState()
    val playing by vm.playing.collectAsState()

    // Nothing advances by itself: the score, the sounds and the diagnosis stay on
    // screen until the learner has read them and taps the next action.
    val hasOutcome = attempt.hasOutcome
    val scrollState = rememberScrollState()
    val drill = state.drill

    // A new drill starts at the top, so the learner reads the phrase first
    // instead of landing where the previous take left the scroll.
    LaunchedEffect(drill?.id) { scrollState.scrollTo(0) }

    // The score and diagnosis can land under the fold; bring them into view the
    // moment they exist, so "next" is never tapped before reading them.
    val resultRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(hasOutcome) { if (hasOutcome) resultRequester.bringIntoView() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEdgeFade(scrollState)
                    .padding(horizontal = Space.gutter)
                    .verticalScroll(scrollState),
            ) {
                if (drill != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.lessons_drill_progress, state.drillIndex + 1, state.drills.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = GlossoColors.accentText,
                            modifier = Modifier.weight(1f),
                        )
                        // Skipping is secondary and only offered before deciding to
                        // record; once there is a result, the honest next action is the
                        // primary one below, not "skip".
                        if (!hasOutcome) {
                            QuietTextButton(
                                text = stringResource(Res.string.practice_skip),
                                onClick = { vm.advanceDrill() },
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.x20))
                    val hasAudio = drill.audio.isNotEmpty()
                    DrillCard(
                        text = drill.text,
                        ipa = drill.displayPhonetic,
                        translation = uiTranslation(drill.translations),
                        focus = drill.focus,
                        hasAudio = hasAudio,
                        onPlay = { vm.play(false) },
                    )
                    if (!hasAudio) {
                        Spacer(Modifier.height(Space.x16))
                        NoAudioNote()
                    }
                    // The recorder is pinned in the footer below, so the body
                    // keeps its room for the phrase, the reference and the result.
                    Spacer(Modifier.height(Space.x8))
                    if (attempt.offlineNoModel) {
                        Spacer(Modifier.height(Space.x32))
                        OnDeviceModelOffer(onDownload = { vm.downloadOnDeviceModel() })
                    }
                    Box(Modifier.bringIntoViewRequester(resultRequester)) {
                        Column {
                            if (attempt.notEvaluated) {
                                Spacer(Modifier.height(Space.x32))
                                NotEvaluatedCard()
                            } else {
                                attempt.result?.let {
                                    Spacer(Modifier.height(Space.x32))
                                    ResultCard(it, previousScore = state.previousScore)
                                    if (hasAudio && it.level in RETRY_LEVELS) {
                                        Spacer(Modifier.height(Space.x16))
                                        HearAgainButton(onClick = { vm.play(false) })
                                    }
                                }
                            }
                        }
                    }
                } else if (state.drillsPending) {
                    LoadingState(Modifier.fillMaxWidth().padding(vertical = Space.x40))
                } else {
                    // The plan came back empty (an empty bank, or offline with
                    // nothing cached). Never breathe forever with no way forward:
                    // say so and let the learner close out the pronunciation step.
                    EmptyState(
                        title = stringResource(Res.string.practice_empty_title),
                        message = stringResource(Res.string.practice_empty_message),
                        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x40),
                    )
                }
                Spacer(Modifier.height(Space.x24))
            }
        }
        // One pinned footer for the whole step: the compact recorder and, once
        // there is an outcome, the action to continue or finish. The body scrolls
        // above it, so the phrase and the result are never covered.
        when {
            drill != null -> RecordingDeck(
                state = deckState(attempt, drill.audio, playing),
                level = { level.value },
                onToggle = { vm.toggleRecord() },
                onPlay = { slow -> vm.play(slow) },
                onPlayTake = { vm.playTake() },
                action = if (hasOutcome) {
                    {
                        val last = state.drillIndex >= state.drills.size - 1
                        PrimaryButton(
                            text = stringResource(
                                if (last) Res.string.lessons_finish_speaking else Res.string.practice_next,
                            ),
                            onClick = { vm.advanceDrill() },
                        )
                    }
                } else {
                    null
                },
            )

            !state.drillsPending -> BottomActionBar {
                PrimaryButton(
                    text = stringResource(Res.string.lessons_continue),
                    onClick = { vm.advanceDrill() },
                )
            }
        }
    }
}

@Composable
private fun SummaryStep(state: LessonState, onDebugReviewDue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.x32, vertical = Space.x24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.drillScores.isNotEmpty()) {
            val average = state.drillScores.average().toInt()
            // The lesson closes on the one number it measured, drawn as the same
            // ring every take uses, with the voice line resting under it.
            StaggeredAppear(index = 0) { ScoreRing(score = average, size = 168.dp, stroke = 10.dp) }
            Spacer(Modifier.height(Space.x8))
            VoiceWave(Modifier.width(180.dp).height(28.dp), alpha = 0.5f)
            Spacer(Modifier.height(Space.x24))
            StaggeredAppear(index = 1) {
                Text(
                    stringResource(Res.string.lessons_completed),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Space.x12))
            StaggeredAppear(index = 2) {
                Text(
                    stringResource(
                        if (ScoringConfig.isMasteryBand(average)) Res.string.lessons_summary_high else Res.string.lessons_summary_keep,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (state.drillScores.size > 1) {
                Spacer(Modifier.height(Space.x24))
                StaggeredAppear(index = 3) {
                    // Every take of the lesson as a small bar, in order: the shape
                    // of the session at a glance.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.x8),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.height(48.dp),
                    ) {
                        state.drillScores.forEach { score ->
                            Box(
                                Modifier
                                    .width(10.dp)
                                    .fillMaxHeight((score / 100f).coerceIn(0.08f, 1f))
                                    .clip(RoundedCornerShape(Radius.full))
                                    .background(accuracyColor(score)),
                            )
                        }
                    }
                }
            }
        } else {
            StaggeredAppear(index = 0) { RewardSeal(size = 96.dp) }
            Spacer(Modifier.height(Space.x8))
            StaggeredAppear(index = 1) {
                Text(
                    stringResource(Res.string.lessons_read),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Space.x12))
            StaggeredAppear(index = 2) {
                Text(
                    stringResource(Res.string.lessons_summary_read),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        if (state.scheduledForReview > 0) {
            // Said plainly, once: these phrases will be asked again. No counter
            // to clear and nothing owed.
            Spacer(Modifier.height(Space.x24))
            Text(
                stringResource(Res.string.lessons_summary_review, state.scheduledForReview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (LocalAppConfig.current.debugUi) {
                QuietTextButton(text = "Test: make reviews due", onClick = onDebugReviewDue)
            }
        }
    }
}

/** How far [step] is through [steps], counting the step itself: the fill of the lesson's ring. */
internal fun stepFraction(steps: List<LessonStep>, step: LessonStep): Float =
    (steps.indexOf(step).coerceAtLeast(0) + 1f) / steps.size

/** "Step 2 of 5 · Exercises" over the step bar, counting only the steps the lesson has. */
@Composable
internal fun LessonStepProgress(steps: List<LessonStep>, step: LessonStep, modifier: Modifier = Modifier) {
    val index = steps.indexOf(step).coerceAtLeast(0)
    LabelledSteps(
        current = index,
        total = steps.size,
        caption = stringResource(Res.string.lessons_step_progress, index + 1, steps.size, stepName(step)),
        modifier = modifier,
    )
}

@Composable
internal fun stepName(step: LessonStep): String = when (step) {
    LessonStep.REVIEW -> stringResource(Res.string.step_review)
    LessonStep.CONTEXT -> stringResource(Res.string.step_context)
    LessonStep.BASE -> stringResource(Res.string.step_base)
    LessonStep.EXERCISES -> stringResource(Res.string.step_exercises)
    LessonStep.SPEAK -> stringResource(Res.string.step_speak)
    LessonStep.DRILLS -> stringResource(Res.string.step_drills)
    LessonStep.SUMMARY -> stringResource(Res.string.step_summary)
}

/** Names the base a lesson opens with: a dialogue, a story or a news piece. */
@Composable
internal fun baseLabel(lesson: LessonModel): String = when {
    lesson.dialogueId != null -> stringResource(Res.string.content_dialogue)
    lesson.storyId != null -> stringResource(Res.string.content_story)
    lesson.articleId != null -> stringResource(Res.string.content_news)
    else -> stringResource(Res.string.content_lesson)
}

/** Dialogue, story, news order inside a level, so each kind stays visible. */
@Composable
private fun LevelButton(level: String, pickable: Boolean, onClick: () -> Unit) {
    // A course that publishes one level has nothing to pick: the level is shown
    // as a plain label, not as a menu that offers the same level again.
    Row(
        modifier = Modifier
            .heightIn(min = Sizes.touch)
            .then(if (pickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (level == "ALL") stringResource(Res.string.lessons_all) else level,
            style = MaterialTheme.typography.labelLarge,
            color = if (pickable) GlossoColors.accentText else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pickable) {
            Spacer(Modifier.width(Space.x2))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(Res.string.lessons_pick_level),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelPickerSheet(
    current: String,
    levels: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.x24),
        ) {
            SectionLabel(stringResource(Res.string.lessons_level))
            Spacer(Modifier.height(Space.x8))
            levels.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = level == current,
                            role = Role.RadioButton,
                            onClick = { onSelect(level) },
                        )
                        .padding(vertical = Space.x16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (level == "ALL") stringResource(Res.string.lessons_all_levels)
                        else stringResource(Res.string.lessons_level_n, level),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (level == current) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (level == current) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Hairline()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(
    lesson: LessonModel,
    offline: OfflineState,
    state: LessonState,
    vm: LessonViewModel,
    onDismiss: () -> Unit,
) {
    val meta = offline.meta(lesson.id)
    val downloading = offline.downloadingId == lesson.id
    val estimating = state.estimatingFor == lesson.id
    val estimate = state.estimateBytes.takeIf { state.estimateFor == lesson.id }
    val online by rememberConnectivity().online.collectAsState()
    val activeItem = LocalDownloader.current.state.collectAsState().value
        .item(DownloadIds.lesson(lesson.id))
    val requestNotifications = rememberNotificationPermissionRequest()

    LaunchedEffect(lesson.id, online) {
        // No point asking the server for a size with no connection: say so
        // plainly instead of showing "calculating" forever.
        if (meta == null && online) vm.requestEstimate(lesson)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.x24),
        ) {
            SectionLabel(stringResource(Res.string.lessons_offline_sheet))
            Spacer(Modifier.height(Space.x8))
            Text(lesson.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.x4))
            Text(
                stringResource(Res.string.label_pair, lesson.level, themeLabel(lesson.theme, state.themeLabels)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x20))

            when {
                downloading -> {
                    ProgressLine(offline.progress)
                    Spacer(Modifier.height(Space.x8))
                    Text(
                        if (activeItem?.status == DownloadStatus.WAITING_NETWORK) {
                            stringResource(Res.string.download_waiting_wifi)
                        } else {
                            stringResource(Res.string.lessons_downloading, (offline.progress * 100).toInt())
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlossoColors.accentText,
                    )
                    if (activeItem != null) {
                        Spacer(Modifier.height(Space.x4))
                        DownloadControls(activeItem)
                    }
                }

                meta != null -> {
                    Text(
                        stringResource(Res.string.lessons_downloaded_bytes, formatBytes(meta.bytes)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = GlossoColors.accentText,
                    )
                    Spacer(Modifier.height(Space.x8))
                    Text(
                        stringResource(Res.string.lessons_offline_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.x20))
                    // Removing is the way out of this state, not its goal: an
                    // outlined action in the danger tone, never a gold call.
                    SecondaryButton(
                        onClick = {
                            vm.deleteDownload(lesson.id)
                            onDismiss()
                        },
                        contentColor = GlossoColors.danger,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.lessons_delete_device), style = MaterialTheme.typography.labelLarge)
                    }
                }

                !online -> {
                    Text(
                        stringResource(Res.string.lessons_offline_needs_connection),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(Space.x20))
                    PrimaryButton(
                        stringResource(Res.string.lessons_download_for_offline),
                        onClick = {},
                        enabled = false,
                    )
                }

                else -> {
                    Text(
                        when {
                            estimating -> stringResource(Res.string.lessons_calculating)
                            estimate != null -> stringResource(Res.string.lessons_about_size, formatBytes(estimate))
                            else -> stringResource(Res.string.lessons_size_unavailable)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(Space.x8))
                    Text(
                        stringResource(Res.string.lessons_download_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.x20))
                    PrimaryButton(
                        stringResource(Res.string.lessons_download_for_offline),
                        onClick = {
                            vm.downloadLesson(lesson)
                            requestNotifications()
                        },
                        enabled = !estimating,
                    )
                    offline.error?.let { message ->
                        Spacer(Modifier.height(Space.x12))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Space.x24))
            Hairline()
            Spacer(Modifier.height(Space.x12))
            Text(
                stringResource(Res.string.lessons_storage, formatBytes(offline.totalBytes)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
