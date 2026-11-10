package com.glosso.app

import com.glosso.app.platform.currentPlatformContext
import com.glosso.app.platform.rememberConnectivity
import com.glosso.app.platform.PlatformBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.product.ProductConfigRepository
import com.glosso.app.product.VersionNotice
import com.glosso.app.resources.*
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.displayPhonetic
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The free modes and settings that live under the Library tab. */
enum class LibraryRoute { STORIES, EXERCISES, PRACTICE, OFFLINE, SETTINGS }

/**
 * The shell's own navigation: which top-level tab is shown and which Library
 * sub-route, if any. Plain state so the two rules that keep the bottom bar
 * honest are testable: the bar owns only the four top-level screens, and a jump
 * into a task's base clears the Library sub-route.
 */
internal data class ShellState(
    val destination: GlossoDestination = GlossoDestination.TODAY,
    val route: LibraryRoute? = null,
    /** The lesson the player had open, so a recreated process can reopen it. */
    val openLessonId: String? = null,
    /** The story the reader had open, for the same reason. */
    val openStoryId: String? = null,
)

/**
 * Keeps the shell across a configuration change and a process death, so a
 * rotation or coming back to a killed app shows the same tab and sub-screen
 * instead of Today. Only enum names and ids are saved (a Bundle on Android).
 */
internal val ShellStateSaver: Saver<ShellState, Any> = listSaver(
    save = { listOf(it.destination.name, it.route?.name, it.openLessonId, it.openStoryId) },
    restore = { saved ->
        ShellState(
            destination = GlossoDestination.entries.firstOrNull { it.name == saved.getOrNull(0) }
                ?: GlossoDestination.TODAY,
            route = LibraryRoute.entries.firstOrNull { it.name == saved.getOrNull(1) },
            openLessonId = saved.getOrNull(2),
            openStoryId = saved.getOrNull(3),
        )
    },
)

/** What to do about a lesson or story that was open before the process was recreated. */
internal sealed interface Reopen<out T> {
    /** The content is still loading: decide once it is there. */
    data object Wait : Reopen<Nothing>

    /** Nothing to reopen (gone, finished, or something else is open): keep the screen behind it. */
    data object GiveUp : Reopen<Nothing>

    data class Open<T>(val item: T) : Reopen<T>
}

/**
 * The lesson [id] the player had open, once [state] has loaded. It reopens at
 * the step the learner left (the saved progress) when that is the lesson in
 * progress, otherwise from its start; a finished lesson leaves the lesson list.
 */
internal fun lessonToReopen(id: String, state: LessonState): Reopen<LessonModel> {
    if (state.active != null) return Reopen.GiveUp
    val lesson = state.lessons.firstOrNull { it.id == id }
    return when {
        lesson != null && state.resumeLessonId == id && state.resumeStep == LessonStep.SUMMARY -> Reopen.GiveUp
        lesson != null -> Reopen.Open(lesson)
        state.error != null || (!state.loading && state.lessons.isNotEmpty()) -> Reopen.GiveUp
        else -> Reopen.Wait
    }
}

/** The story [id] the reader had open, once [state] has loaded. */
internal fun storyToReopen(id: String, state: StoriesState): Reopen<StoryModel> {
    if (state.reading != null) return Reopen.GiveUp
    val story = state.stories.firstOrNull { it.id == id }
    return when {
        story != null -> Reopen.Open(story)
        state.error != null || (!state.loading && state.stories.isNotEmpty()) -> Reopen.GiveUp
        else -> Reopen.Wait
    }
}

/**
 * The bottom bar shows only on the four top-level screens: the lesson player,
 * the story reader and every Library sub-screen carry their own chrome (back
 * arrow, pinned footer) instead.
 */
internal fun ShellState.showsBottomBar(lessonActive: Boolean, storyReading: Boolean): Boolean =
    !lessonActive && !storyReading && route == null

/**
 * Moves the shell to the LESSONS tab and clears any Library sub-route in one
 * step. Browsing the course and jumping into a task's base both land here;
 * clearing is not cosmetic, because a stale route keeps the bottom bar hidden
 * once the lesson player closes and the lesson list has no back arrow of its own.
 */
internal fun ShellState.openLessons(): ShellState =
    copy(destination = GlossoDestination.LESSONS, route = null)

/** The shell's own screen, as `screen_view` names it (server `ClientEvents.SCREENS`). */
internal fun ShellState.screenName(lessonActive: Boolean, storyReading: Boolean): String = when {
    lessonActive -> "lesson"
    storyReading -> "story"
    destination == GlossoDestination.LIBRARY -> when (route) {
        null -> "library"
        LibraryRoute.STORIES -> "stories"
        LibraryRoute.EXERCISES -> "exercises"
        LibraryRoute.PRACTICE -> "practice"
        LibraryRoute.OFFLINE -> "offline"
        LibraryRoute.SETTINGS -> "settings"
    }
    else -> when (destination) {
        GlossoDestination.TODAY -> "today"
        GlossoDestination.LESSONS -> "lessons"
        GlossoDestination.YOU -> "you"
        GlossoDestination.LIBRARY -> "library"
    }
}

@Composable
fun GlossoApp() {
    var shell by rememberSaveable(stateSaver = ShellStateSaver) { mutableStateOf(ShellState()) }
    val destination = shell.destination
    val route = shell.route

    val lessonVm: LessonViewModel = lessonViewModel()
    val storiesVm: StoriesViewModel = storiesViewModel()
    val lessonState by lessonVm.state.collectAsState()
    val storiesState by storiesVm.state.collectAsState()

    // After a process death the ViewModels start empty while the shell comes
    // back saved: reopen the lesson or story it had open once the content is
    // there. After a rotation the ViewModels survive, so there is nothing to do.
    var lessonToRestore by remember { mutableStateOf(shell.openLessonId.takeIf { lessonState.active == null }) }
    var storyToRestore by remember { mutableStateOf(shell.openStoryId.takeIf { storiesState.reading == null }) }
    LaunchedEffect(lessonToRestore, lessonState) {
        val id = lessonToRestore ?: return@LaunchedEffect
        when (val reopen = lessonToReopen(id, lessonState)) {
            Reopen.Wait -> Unit
            Reopen.GiveUp -> lessonToRestore = null
            is Reopen.Open -> {
                lessonToRestore = null
                if (lessonState.resumeLessonId == id) lessonVm.resume() else lessonVm.openLesson(reopen.item)
            }
        }
    }
    LaunchedEffect(storyToRestore, storiesState) {
        val id = storyToRestore ?: return@LaunchedEffect
        when (val reopen = storyToReopen(id, storiesState)) {
            Reopen.Wait -> Unit
            Reopen.GiveUp -> storyToRestore = null
            is Reopen.Open -> {
                storyToRestore = null
                storiesVm.openStory(reopen.item)
            }
        }
    }
    val productConfig by ProductConfigRepository.config.collectAsState()
    val config = LocalAppConfig.current
    val onboardingDone by OnboardingStore.done.collectAsState()
    val connectivity = rememberConnectivity()
    val online by connectivity.online.collectAsState()
    val scoringVm: ScoringViewModel = scoringViewModel()
    val scorer by scoringVm.state.collectAsState()
    val offlineHintDismissed by OfflineModelHint.dismissed.collectAsState()
    val requestNotifications = rememberNotificationPermissionRequest()

    // The offline strip's offer is a one-time tip, not a loop: it only appears
    // while offline without the model, and never again once hidden or started.
    val showOfflineModelNudge = LocalAppServices.current.onDevice.supported && !online && !scorer.present &&
        !scorer.downloading && !scorer.preparing && !offlineHintDismissed

    // A newer build may be recommended, but the notice only warns and is
    // dismissible: nothing here blocks the learner.
    val versionNotice = remember(productConfig) {
        VersionNotice.from(productConfig, config.versionCode)
    }
    var updateDismissed by rememberSaveable { mutableStateOf(false) }

    // Recover on its own the moment the connection is back: reload the catalog
    // so a screen that went empty or stale while offline fills in without a
    // restart. Only the offline -> online edge refreshes, so a cold start (which
    // loads anyway) does not double the work.
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) {
        if (online && !wasOnline) {
            lessonVm.refresh()
            // Never pull the list out from under an open reader: only reload the
            // stories bank when none is being read.
            if (storiesState.reading == null) storiesVm.load()
        }
        wasOnline = online
    }

    // Re-validate on every return to the foreground: the connection, the server
    // flags and the version policy may have changed while backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = currentPlatformContext()
    val resumeScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                connectivity.refresh()
                resumeScope.launch { ProductConfigRepository.refresh() }
                resumeScope.launch { AccentScoringFlag.refresh(appApi) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The lesson player, the story reader and every Library sub-screen (each
    // carries its own back arrow and, where it records, its own pinned footer)
    // own the whole window, so the bar shows only on the four top-level screens.
    val isPlayingLesson = destination == GlossoDestination.LESSONS && lessonState.active != null
    val isReadingStory = destination == GlossoDestination.LIBRARY && route == LibraryRoute.STORIES && storiesState.reading != null
    val showBar = shell.showsBottomBar(lessonActive = isPlayingLesson, storyReading = isReadingStory)
    val baseScreen = shell.screenName(lessonActive = isPlayingLesson, storyReading = isReadingStory)
    LaunchedEffect(baseScreen) { ScreenTracker.setBase(baseScreen) }

    // What is open goes into the saved shell, but only once a pending restore is
    // settled, so the saved id is not cleared before it can be reopened.
    val openLessonId = lessonState.active?.id.takeIf { isPlayingLesson }
    val openStoryId = storiesState.reading?.id.takeIf { isReadingStory }
    LaunchedEffect(openLessonId, openStoryId, lessonToRestore, storyToRestore) {
        shell = shell.copy(
            openLessonId = if (lessonToRestore == null) openLessonId else shell.openLessonId,
            openStoryId = if (storyToRestore == null) openStoryId else shell.openStoryId,
        )
    }

    // Back walks the app's own stack first; only the Today root exits.
    val canGoBack = isPlayingLesson ||
        isReadingStory ||
        route != null ||
        destination != GlossoDestination.TODAY
    PlatformBackHandler(enabled = canGoBack) {
        when {
            isPlayingLesson -> lessonVm.closeLesson()
            isReadingStory -> storiesVm.closeStory()
            route != null -> shell = shell.copy(route = null)
            else -> shell = shell.copy(destination = GlossoDestination.TODAY)
        }
    }

    // Today's one tap: continue the lesson in progress, or open the next one in
    // teaching order. LessonsScreen renders the player, so the destination moves
    // there first.
    val progress by ProgressStore.state.collectAsState()
    val today = remember(lessonState.lessons, lessonState.themeOrder, lessonState.resumeLessonId, lessonState.resumeStep, progress.finishedLessons) {
        todayPlan(
            lessons = lessonState.lessons,
            themeOrder = lessonState.themeOrder,
            resumeLessonId = lessonState.resumeLessonId,
            resumeInProgress = lessonState.resumeStep != LessonStep.SUMMARY,
            finishedLessonIds = progress.finishedLessons,
        )
    }
    val startTodayLesson: () -> Unit = {
        shell = shell.openLessons()
        when {
            today.resumable -> lessonVm.resume()
            else -> today.lesson?.let { lessonVm.openLesson(it) }
        }
    }
    val browseLessons: () -> Unit = {
        shell = shell.openLessons()
    }
    val openTodayReview: () -> Unit = {
        shell = shell.copy(destination = GlossoDestination.LIBRARY, route = LibraryRoute.PRACTICE)
    }
    val openTodaySettings: () -> Unit = {
        shell = shell.copy(destination = GlossoDestination.LIBRARY, route = LibraryRoute.SETTINGS)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.weight(1f)) {
                val reduce = LocalReduceMotion.current
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        if (reduce) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (
                                slideInHorizontally(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ) { width -> dir * width / 4 } +
                                    fadeIn(tween(Motion.base, easing = Motion.decelerate)) +
                                    scaleIn(initialScale = 0.99f)
                                ) togetherWith (
                                slideOutHorizontally(
                                    animationSpec = tween(Motion.base, easing = Motion.accelerate),
                                ) { width -> -dir * width / 6 } +
                                    fadeOut(tween(Motion.fast))
                                )
                        }
                    },
                    label = "destination",
                ) { dest ->
                    when (dest) {
                        GlossoDestination.TODAY -> TodayScreen(
                            plan = today,
                            step = lessonState.resumeStep.takeIf { today.resumable },
                            loading = lessonState.loading && lessonState.lessons.isEmpty(),
                            hasLessons = lessonState.lessons.isNotEmpty(),
                            offline = !online,
                            onStartLesson = startTodayLesson,
                            onBrowseLessons = browseLessons,
                            onReviewSounds = openTodayReview,
                            onSettings = openTodaySettings,
                            error = lessonState.error,
                            onRetry = { lessonVm.refresh() },
                        )

                        GlossoDestination.LESSONS -> LessonsScreen(vm = lessonVm)

                        GlossoDestination.YOU -> YouScreen(
                            onPractice = {
                                shell = shell.copy(destination = GlossoDestination.LIBRARY, route = LibraryRoute.PRACTICE)
                            },
                        )

                        GlossoDestination.LIBRARY -> LibraryRoutes(
                            route = route,
                            storiesVm = storiesVm,
                            lessonVm = lessonVm,
                            onOpen = { shell = shell.copy(route = it) },
                            onOpenLesson = { lesson ->
                                shell = shell.openLessons()
                                lessonVm.openLesson(lesson, LessonStep.BASE)
                            },
                            onBack = { shell = shell.copy(route = null) },
                        )
                    }
                }
            }
            // With the bar present it already clears the gesture inset; alone
            // (lesson player, story reader) the strip must clear it itself.
            if (versionNotice.visible && !updateDismissed) {
                UpdateNotice(
                    notice = versionNotice,
                    onDismiss = { updateDismissed = true },
                    modifier = if (showBar) Modifier else Modifier.navigationBarsPadding(),
                )
            }
            OfflineBanner(
                visible = !online,
                modifier = if (showBar) Modifier else Modifier.navigationBarsPadding(),
            ) {
                if (showOfflineModelNudge) {
                    OfflineModelNudge(
                        sizeText = onDeviceModelSize(),
                        onDownload = {
                            scoringVm.download()
                            requestNotifications()
                        },
                        onHide = { OfflineModelHint.dismiss(context) },
                    )
                }
            }
            if (showBar) {
                GlossoBottomBar(
                    current = destination,
                    onSelect = { shell = shell.copy(destination = it, route = null) },
                )
            }
        }
        AchievementOverlay(modifier = Modifier.align(Alignment.TopCenter))
    }

    // First run only: the short welcome, over the course, until it is dismissed.
    // The guard keeps a tap on its empty areas from reaching the app behind.
    if (!onboardingDone) {
        TrackScreen("onboarding")
        Box(Modifier.fillMaxSize().consumePointerEvents())
        OnboardingScreen(onFinish = { OnboardingStore.complete() })
    }
}

@Composable
private fun LibraryRoutes(
    route: LibraryRoute?,
    storiesVm: StoriesViewModel,
    lessonVm: LessonViewModel,
    onOpen: (LibraryRoute) -> Unit,
    onOpenLesson: (LessonModel) -> Unit,
    onBack: () -> Unit,
) {
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            val forward = (targetState != null)
            if (forward) {
                (fadeIn(tween(Motion.base)) + slideInHorizontally(tween(Motion.base)) { it / 6 }) togetherWith
                    (fadeOut(tween(Motion.fast)) + slideOutHorizontally(tween(Motion.base)) { -it / 8 })
            } else {
                (fadeIn(tween(Motion.base)) + slideInHorizontally(tween(Motion.base)) { -it / 6 }) togetherWith
                    (fadeOut(tween(Motion.fast)))
            }
        },
        label = "libraryRoute",
    ) { r ->
        when (r) {
            null -> LibraryScreen(onOpen = onOpen)

            LibraryRoute.STORIES -> StoriesScreen(vm = storiesVm, onBack = onBack)

            LibraryRoute.EXERCISES -> ExercisesScreen(
                onOpenSource = { source ->
                    when (source) {
                        is ExerciseSource.Story -> {
                            storiesVm.openStory(source.story)
                            onOpen(LibraryRoute.STORIES)
                        }
                        is ExerciseSource.Lesson -> onOpenLesson(source.lesson)
                    }
                },
                onBack = onBack,
            )

            LibraryRoute.PRACTICE -> PracticeScreen(onBack = onBack)

            LibraryRoute.OFFLINE -> OfflineScreen(vm = lessonVm, onBack = onBack)

            LibraryRoute.SETTINGS -> SettingsScreen(onBack = onBack)
        }
    }
}

@Composable
private fun LibraryScreen(onOpen: (LibraryRoute) -> Unit) {
    val scroll = rememberScrollState()
    val collapse = rememberCollapse(scroll)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopHeader(
            title = stringResource(Res.string.library_title),
            collapse = { collapse.value },
        )
        Column(
            Modifier
                .padding(horizontal = Space.gutter)
                .verticalScroll(scroll),
        ) {
            Spacer(Modifier.height(Space.x20))
            // Free practice leads, on the warm wash: it is the one mode here that
            // trains the voice. The rest stay a quiet list, not a wall of cards.
            FeaturedTile(
                icon = Icons.Outlined.Mic,
                title = stringResource(Res.string.library_practice_title),
                subtitle = stringResource(Res.string.library_practice_subtitle),
                onClick = { onOpen(LibraryRoute.PRACTICE) },
            )
            Spacer(Modifier.height(Space.x12))
            HubRow(
                icon = Icons.Outlined.AutoStories,
                title = stringResource(Res.string.library_stories_title),
                subtitle = stringResource(Res.string.library_stories_subtitle),
                onClick = { onOpen(LibraryRoute.STORIES) },
            )
            Hairline()
            HubRow(
                icon = Icons.Outlined.Quiz,
                title = stringResource(Res.string.library_exercises_title),
                subtitle = stringResource(Res.string.library_exercises_subtitle),
                onClick = { onOpen(LibraryRoute.EXERCISES) },
            )
            Hairline()
            HubRow(
                icon = Icons.Outlined.DownloadForOffline,
                title = stringResource(Res.string.library_offline_title),
                subtitle = stringResource(Res.string.library_offline_subtitle),
                onClick = { onOpen(LibraryRoute.OFFLINE) },
            )
            Hairline()
            HubRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(Res.string.library_settings_title),
                subtitle = stringResource(Res.string.library_settings_subtitle),
                onClick = { onOpen(LibraryRoute.SETTINGS) },
            )
            Hairline()
            Spacer(Modifier.height(Space.x40))
        }
    }
}

/**
 * The Library's lead door: a brass medallion, the name and one line, on the
 * warm wash with a gold edge, full width.
 */
@Composable
private fun FeaturedTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Row(
            Modifier.background(heroWash()).padding(Space.x20),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = GlossoColors.accentText, modifier = Modifier.size(Sizes.icon))
            }
            Spacer(Modifier.width(Space.x16))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(Space.x4))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Space.x8))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = GlossoColors.accentText,
                modifier = Modifier.size(Sizes.icon),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PracticeScreen(
    vm: PracticeViewModel = practiceViewModel(),
    onBack: () -> Unit,
) {
    val config = LocalAppConfig.current
    val state by vm.state.collectAsState()
    val attempt by vm.attempt.collectAsState()
    val level = vm.level.collectAsState()
    val playing by vm.playing.collectAsState()
    val total = state.drills.size.coerceAtLeast(1)
    // Re-plan on every entry, so a profile recorded since the last visit turns
    // the general bank into the personalised plan.
    LaunchedEffect(Unit) { vm.load() }
    val personalized = state.source == PracticeSource.PROFILE

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(
            title = stringResource(
                if (personalized) Res.string.practice_title_personalized else Res.string.practice_title,
            ),
            onBack = onBack,
            // Store-capture build only: an invisible tap target that injects a
            // scored take, so tools/store/capture.py can reach the feedback screen
            // on an emulator with no microphone while the visible chrome stays
            // clean. Inert in every other build.
            trailing = if (config.debug && config.storeScreenshots) {
                {
                    Box(
                        Modifier
                            .size(Sizes.touch)
                            .alpha(0f)
                            .clickable { vm.debugInjectFixture(bad = true) },
                    )
                }
            } else {
                null
            },
        )

        practiceSourceNote(state.source)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.x2),
            )
        }

        if (config.debugUi) {
            DebugFixtureBar(
                onBadTake = { vm.debugInjectFixture(bad = true) },
                onGoodTake = { vm.debugInjectFixture(bad = false) },
                onNativeTake = if (config.prosodyBonus) ({ vm.debugNativeTake() }) else null,
            )
        }

        when {
            state.loading && state.drills.isEmpty() ->
                LoadingState(Modifier.weight(1f))

            state.drill == null -> if (state.error != null) {
                ErrorState(onRetry = { vm.load() }, modifier = Modifier.weight(1f), detail = state.error)
            }             else {
                EmptyState(
                    title = stringResource(Res.string.practice_empty_title),
                    message = stringResource(Res.string.practice_empty_message),
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                val drill = state.drill!!
                val phase = when {
                    attempt.result != null -> stringResource(Res.string.phase_score)
                    attempt.recording -> stringResource(Res.string.practice_repeat)
                    else -> stringResource(Res.string.practice_listen)
                }
                val scrollState = rememberScrollState()
                // A new drill starts at the top, so the phrase is read first
                // instead of landing where the previous take left the scroll.
                LaunchedEffect(drill.id, state.index) { scrollState.scrollTo(0) }
                // The score and diagnosis can land under the fold; bring them
                // into view the moment they exist, so "next" is never tapped
                // before reading them.
                val resultRequester = remember { BringIntoViewRequester() }
                LaunchedEffect(attempt.hasOutcome) {
                    if (attempt.hasOutcome) resultRequester.bringIntoView()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEdgeFade(scrollState)
                        .padding(horizontal = Space.gutter)
                        .verticalScroll(scrollState),
                ) {
                    Spacer(Modifier.height(Space.x4))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(Res.string.practice_counter, state.index + 1, total),
                            style = MaterialTheme.typography.labelMedium,
                            color = GlossoColors.accentText,
                        )
                        Spacer(Modifier.width(Space.x12))
                        Box(Modifier.weight(1f)) {
                            if (total <= MAX_SEGMENTS) {
                                StepProgress(total = total, current = state.index)
                            } else {
                                ProgressLine((state.index + 1f) / total)
                            }
                        }
                        // Skipping a drill is secondary and only offered before
                        // deciding to record; once there is an outcome, the honest
                        // next action is the primary "next exercise" below.
                        if (!attempt.hasOutcome) {
                            Spacer(Modifier.width(Space.x4))
                            QuietTextButton(
                                stringResource(Res.string.practice_skip),
                                onClick = { vm.next() },
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.x24))
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
                        OnDeviceModelOffer(onDownload = { vm.downloadModel() })
                    }
                    Box(Modifier.bringIntoViewRequester(resultRequester)) {
                        Column {
                            if (attempt.notEvaluated) {
                                Spacer(Modifier.height(Space.x32))
                                NotEvaluatedCard()
                            } else {
                                attempt.result?.let {
                                    Spacer(Modifier.height(Space.x32))
                                    ResultCard(
                                        feedback = it,
                                        onPracticeError = { error -> vm.practiceError(error) },
                                        focus = state.focus,
                                        practiceUnavailable = state.drillUnavailable,
                                        previousScore = state.previousScore,
                                    )
                                    if (hasAudio && it.level in RETRY_LEVELS) {
                                        Spacer(Modifier.height(Space.x16))
                                        HearAgainButton(onClick = { vm.play(false) })
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.x40))
                }
                TransientPhaseIndicator(
                    text = phase,
                    phaseKey = phase,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Space.x12),
                )
                }
                // One pinned footer for the whole loop: the compact recorder and,
                // once there is an outcome, the next action. The body scrolls
                // above it, so neither the phrase nor the result is covered.
                RecordingDeck(
                    state = deckState(attempt, drill.audio, playing),
                    level = { level.value },
                    onToggle = { vm.toggleRecord() },
                    onPlay = { slow -> vm.play(slow) },
                    onPlayTake = { vm.playTake() },
                    action = if (attempt.hasOutcome) {
                        {
                            PrimaryButton(
                                text = stringResource(Res.string.practice_next),
                                onClick = { vm.next() },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Up to this many drills, progress is drawn as one segment per drill. */
private const val MAX_SEGMENTS = 10

/**
 * The honest one-liner about where the practice list came from, if worth saying.
 */
@Composable
private fun practiceSourceNote(source: PracticeSource): String? = when (source) {
    PracticeSource.PROFILE -> stringResource(Res.string.practice_source_profile)
    PracticeSource.NO_PLAN -> stringResource(Res.string.practice_source_no_plan)
    PracticeSource.GENERAL -> null
}

/**
 * Debug-only controls: they inject a scored take with no microphone so the whole
 * loop can be exercised on an emulator. Compiled but inert in release builds.
 */
@Composable
private fun DebugFixtureBar(onBadTake: () -> Unit, onGoodTake: () -> Unit, onNativeTake: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DEBUG",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        QuietTextButton("/θ/ mala", onClick = onBadTake)
        QuietTextButton("Buena", onClick = onGoodTake)
        onNativeTake?.let { QuietTextButton("Nativa", onClick = it) }
    }
}
