package com.glosso.app

import com.glosso.app.download.DownloadIds
import com.glosso.app.download.LocalDownloader
import com.glosso.app.platform.APP_LOCALE_TAGS
import com.glosso.app.platform.currentPlatformContext
import com.glosso.app.platform.rememberConnectivity
import com.glosso.app.platform.rememberPlatformActions
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glosso.app.api.AccountState
import com.glosso.app.api.ProductConfig
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.LocalReduceMotion
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.download.DownloadItemState
import com.glosso.app.product.ProductConfigRepository
import com.glosso.app.resources.*
import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.model.displayPhonetic
import org.jetbrains.compose.resources.stringResource

/**
 * The first-run welcome. Three short, skippable screens: what Glosso promises
 * and which language you are learning, how a lesson works, and a handful of
 * phrases that seed the phonetic profile so it does not start empty. Nothing
 * here is decorative: each line is something a new learner needs in the first
 * ten minutes, and every step can be left behind.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val vm: OnboardingViewModel = onboardingViewModel()
    val scoringVm: ScoringViewModel = scoringViewModel()
    val seed by vm.seed.collectAsState()
    val attempt by vm.attempt.collectAsState()
    val playing by vm.playing.collectAsState()
    val level = vm.level.collectAsState()
    val productConfig by ProductConfigRepository.config.collectAsState()
    val account by ProductConfigRepository.account.collectAsState()
    val scorer by scoringVm.state.collectAsState()
    val downloads by LocalDownloader.current.state.collectAsState()
    val online by rememberConnectivity().online.collectAsState()
    val context = currentPlatformContext()
    val requestNotifications = rememberNotificationPermissionRequest()

    var page by remember { mutableIntStateOf(0) }
    var targetLanguage by remember { mutableStateOf(LanguagePreferences.current.code) }

    // Usage statistics: once per onboarding, and whether it ended by the start
    // button after the seed or by one of the skips.
    var startTracked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!startTracked) {
            startTracked = true
            Analytics.track("onboarding_started")
        }
    }
    val finish: (skipped: Boolean) -> Unit = { skipped ->
        Analytics.track("onboarding_completed", mapOf("steps" to page + 1, "skipped" to skipped))
        onFinish()
    }

    val canStart = page >= LAST_PAGE
    val seedDone = seed.finished || seed.error != null

    // A Scaffold keeps the recorder anchored in the bottom bar: it is measured
    // independently of the (scrollable) page body, so on a short screen or with a
    // large font scale the body shrinks instead of pushing the mic off the
    // bottom. The recorder is the whole point of the seed page, so it never
    // leaves the fold; it is the primary action of the screen.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                // A hairline marks where the body stops: on a short screen the
                // phrase scrolls up under it, so content that reaches the edge
                // reads as "more below", not as a control clipped by the recorder.
                Hairline()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Space.gutter)
                        .padding(top = Space.x12, bottom = Space.x16),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // While a seed phrase is being worked on, the footer holds only
                    // the compact recorder and the page dots. The phrase, the
                    // Normal/Slow row, the outcome and the explicit continue/skip all
                    // live in the scrollable body, so on a short screen or with a
                    // large font nothing is cut against the footer. The recorder
                    // stays pinned, so recording is always one tap away (#79).
                    val drill = seed.drill.takeIf { page == LAST_PAGE && !seedDone }
                    val showRecorder = drill != null
                    if (drill != null) {
                        DeckControls(
                            state = deckState(attempt, drill.audio, playing),
                            level = { level.value },
                            onToggle = { vm.toggleRecord() },
                            onPlay = { slow -> vm.play(slow) },
                            onPlayTake = { vm.playTake() },
                        )
                        Spacer(Modifier.height(Space.x12))
                    }
                    PageDots(count = PAGE_COUNT, current = page)
                    if (!showRecorder) {
                        Spacer(Modifier.height(Space.x20))
                        when {
                            page < LAST_PAGE -> PrimaryButton(
                                text = stringResource(Res.string.onboarding_continue),
                                onClick = { page += 1 },
                            )

                            seedDone -> PrimaryButton(
                                text = stringResource(Res.string.onboarding_start),
                                onClick = { finish(false) },
                            )

                            else -> Text(
                                text = stringResource(Res.string.onboarding_seed_need_take),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canStart && !seedDone) {
                            Spacer(Modifier.height(Space.x4))
                            QuietTextButton(
                                text = stringResource(Res.string.onboarding_seed_skip),
                                onClick = { finish(true) },
                            )
                        }
                    }
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.gutter, end = Space.x12, top = Space.x16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlossoWordmark(Modifier.weight(1f))
                // The header skip leaves the whole onboarding. On the seed page
                // the footer already offers the same exit with a clearer label, so
                // the header one is hidden there to avoid two identical exits.
                if (page < LAST_PAGE) {
                    QuietTextButton(stringResource(Res.string.onboarding_skip), onClick = { finish(true) })
                }
            }

            Box(Modifier.weight(1f)) {
                when (page) {
                    0 -> PromisePage(
                        targetLanguage = targetLanguage,
                        config = productConfig,
                        account = account,
                        onTargetLanguage = { code ->
                            LanguagePreferences.set(code)
                            OfferedLanguages.orNull(code)?.let { VoicePreferences.onLanguageChanged(it) }
                            ProfileStore.get(context).setLanguage(code)
                            targetLanguage = code
                        },
                    )

                    1 -> HowPage(
                        scorer = scorer,
                        modelItem = downloads.item(DownloadIds.MODEL),
                        online = online,
                        sizeText = onDeviceModelSize(),
                        onDownload = {
                            scoringVm.download()
                            requestNotifications()
                        },
                    )

                    else -> SeedPage(
                        seed = seed,
                        attempt = attempt,
                        onPlay = { vm.play(false) },
                        onNext = { vm.next() },
                        onSkip = { finish(true) },
                        onRetry = { vm.load() },
                        onDebugTake = { vm.debugInjectTake() },
                    )
                }
            }
        }
    }
}

private const val PAGE_COUNT = 3
private const val LAST_PAGE = PAGE_COUNT - 1

@Composable
private fun PromisePage(
    targetLanguage: String,
    config: ProductConfig,
    account: AccountState?,
    onTargetLanguage: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .scrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = Space.gutter)
            .padding(top = Space.x24),
    ) {
        // Kept short: with two courses to choose from, a three-line title (German)
        // must still leave both of them above the fold.
        SpeakingBars(Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(Space.x16))
        Text(
            text = stringResource(Res.string.onboarding_title),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.x16))
        Text(
            text = stringResource(Res.string.onboarding_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // What the learner studies comes before the beta note: the next pages
        // already speak in the chosen accent, so the choice must not sit below the fold.
        Spacer(Modifier.height(Space.x24))
        TargetLanguageChoice(selected = targetLanguage, onSelect = onTargetLanguage)
        AccentChoice(targetLanguage)
        Spacer(Modifier.height(Space.x32))
        BetaBanner(config = config, account = account)
        if (rememberPlatformActions().appLocaleSupported) {
            Spacer(Modifier.height(Space.x32))
            AppLanguageChoice()
        }
        Spacer(Modifier.height(Space.x32))
        Text(
            text = stringResource(Res.string.onboarding_analytics_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.x24))
    }
}

/**
 * The language the learner is studying. Only the packs the product actually
 * offers appear, so the choice can never point at a half-built bank; today that
 * is English, and an added pack shows up here without touching this screen.
 */
@Composable
private fun TargetLanguageChoice(selected: String, onSelect: (String) -> Unit) {
    SectionLabel(stringResource(Res.string.onboarding_target_language))
    Spacer(Modifier.height(Space.x8))
    Text(
        text = stringResource(Res.string.onboarding_target_language_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.x12))
    OfferedLanguages.all.forEach { pack ->
        val active = pack.code == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(pack.code) }
                .padding(vertical = Space.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = languageDisplay(pack.code, pack.displayName, pack.nativeName),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = GlossoColors.accentText,
                )
            }
        }
        Hairline()
    }
}

/**
 * The accent the learner wants to hear and learn, when the language offers more
 * than one. It decides the reference voice and the wording of the course from the
 * first phrase, so it is asked here and not left for Settings; a language with a
 * single accent shows nothing.
 */
@Composable
private fun AccentChoice(targetLanguage: String) {
    val pack = OfferedLanguages.orNull(targetLanguage) ?: return
    if (pack.accents.size < 2) return
    val selected by VoicePreferences.accentFlow.collectAsState()
    Spacer(Modifier.height(Space.x32))
    SectionLabel(stringResource(Res.string.onboarding_accent))
    Spacer(Modifier.height(Space.x8))
    Text(
        text = stringResource(Res.string.onboarding_accent_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.x12))
    pack.accents.forEach { accent ->
        val active = accent.code == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { VoicePreferences.set(accent.code) }
                .padding(vertical = Space.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = accentDisplay(accent.code, accent.label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = GlossoColors.accentText,
                )
            }
        }
        Hairline()
    }
}

@Composable
private fun HowPage(
    scorer: OnDeviceScorerState,
    modelItem: DownloadItemState?,
    online: Boolean,
    sizeText: String,
    onDownload: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .scrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = Space.gutter)
            .padding(top = Space.x24),
    ) {
        Text(
            text = stringResource(Res.string.onboarding_how_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.x8))
        Text(
            text = stringResource(Res.string.onboarding_how_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.x32))
        HowStep(
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            title = stringResource(Res.string.onboarding_how_read_title),
            detail = stringResource(Res.string.onboarding_how_read_detail),
        )
        HowStep(
            icon = Icons.Outlined.Quiz,
            title = stringResource(Res.string.onboarding_how_practice_title),
            detail = stringResource(Res.string.onboarding_how_practice_detail),
        )
        HowStep(
            icon = Icons.Outlined.GraphicEq,
            title = stringResource(Res.string.onboarding_how_speak_title),
            detail = stringResource(Res.string.onboarding_how_speak_detail),
        )
        if (LocalAppServices.current.onDevice.supported) {
            Spacer(Modifier.height(Space.x24))
            OfflineOffer(
                scorer = scorer,
                modelItem = modelItem,
                online = online,
                sizeText = sizeText,
                onDownload = onDownload,
            )
        }
        Spacer(Modifier.height(Space.x24))
    }
}

/**
 * The optional offline offer: it names what the model buys, its size, and that
 * it can be left for later, without blocking the rest of the walkthrough. If
 * the pack is already on the device it simply says so; with no connection it
 * waits for one instead of showing a button that would not work.
 */
@Composable
private fun OfflineOffer(
    scorer: OnDeviceScorerState,
    modelItem: DownloadItemState?,
    online: Boolean,
    sizeText: String,
    onDownload: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.icon),
                )
                Spacer(Modifier.width(Space.x12))
                Text(
                    text = stringResource(Res.string.onboarding_offline_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(Res.string.onboarding_offline_body, sizeText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x16))
            when {
                scorer.present -> {
                    Text(
                        text = stringResource(Res.string.settings_ready),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        text = formatBytes(scorer.bytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                scorer.downloading || scorer.preparing ->
                    OnDevicePackControl(scorer, modelItem, sizeText, onDownload, null)

                !online -> Text(
                    text = stringResource(Res.string.lessons_offline_needs_connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> OnDevicePackControl(scorer, modelItem, sizeText, onDownload, null)
            }
        }
    }
}

/**
 * The seed: a few reviewed phrases the learner says, scored through the same
 * engine as practice, so the profile has real evidence from the first minute.
 * If the bank cannot be reached, the step says so and lets the learner move on
 * instead of blocking the first run.
 */
@Composable
private fun SeedPage(
    seed: SeedState,
    attempt: AttemptState,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onDebugTake: () -> Unit,
) {
    // The step ends in one of two settled states, and each one carries its own
    // title so the screen never keeps inviting a measurement that is already
    // over: the bank could not be fetched (retry), or the profile has data
    // (done). Both are short, so they are centred in the body instead of ringing
    // the top and leaving the screen half empty.
    if (seed.finished) {
        SeedMessage(
            title = stringResource(Res.string.onboarding_seed_done_title),
            body = stringResource(Res.string.onboarding_seed_done_body),
            icon = { SealMark(unlocked = true, size = 72.dp) },
        )
        return
    }
    val error = seed.error
    if (error != null) {
        SeedMessage(
            title = stringResource(Res.string.onboarding_seed_error_title),
            body = error,
            icon = {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            },
        ) {
            PrimaryButton(stringResource(Res.string.retry), onClick = onRetry)
        }
        return
    }

    val scroll = rememberScrollState()
    // Once the take is answered (a score, or an honest "not evaluated"), bring
    // the outcome into view: it is the value of this page and it must not need a
    // guess-scroll. It never moves on by itself.
    LaunchedEffect(attempt.result, attempt.notEvaluated) {
        if (attempt.result != null || attempt.notEvaluated) {
            // Scroll past the end on purpose: ScrollState clamps to the real
            // maximum, so this cannot fall short if the layout is still settling
            // when the outcome arrives.
            scroll.animateScrollTo(Int.MAX_VALUE)
        }
    }
    // A new phrase starts at the top. Without this the previous phrase's outcome
    // auto-scroll carried over, leaving the next phrase already scrolled past its
    // card and stuck at the old outcome position.
    LaunchedEffect(seed.index) {
        scroll.scrollTo(0)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .scrollEdgeFade(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = Space.gutter)
            .padding(top = Space.x24),
    ) {
        Text(
            text = stringResource(Res.string.onboarding_seed_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.x8))
        Text(
            text = stringResource(Res.string.onboarding_seed_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.x24))

        when {
            seed.loading -> LoadingState(Modifier.fillMaxWidth().height(160.dp))

            else -> seed.drill?.let { drill ->
                val hasAudio = drill.audio.isNotEmpty()
                Text(
                    text = stringResource(
                        Res.string.onboarding_seed_progress,
                        seed.index + 1,
                        seed.drills.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x16))
                DrillCard(
                    text = drill.text,
                    ipa = drill.displayPhonetic,
                    translation = uiTranslation(drill.translations),
                    focus = drill.focus,
                    hasAudio = hasAudio,
                    onPlay = onPlay,
                )
                if (!hasAudio) {
                    Spacer(Modifier.height(Space.x16))
                    NoAudioNote()
                }
                if (attempt.notEvaluated) {
                    Spacer(Modifier.height(Space.x32))
                    NotEvaluatedCard()
                } else {
                    attempt.result?.let {
                        Spacer(Modifier.height(Space.x32))
                        ResultCard(feedback = it, previousScore = seed.previousScore)
                    }
                }
                if (LocalAppConfig.current.debugUi) {
                    Spacer(Modifier.height(Space.x24))
                    QuietTextButton(
                        text = stringResource(Res.string.onboarding_seed_debug),
                        onClick = onDebugTake,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // The explicit action lives here, in the scroll, right after the
                // result it follows: the footer is only the recorder. "Next
                // phrase" appears once the take has an outcome (a score, or an
                // honest "nothing heard"); before then a quiet line says what is
                // missing. Nothing advances silently.
                Spacer(Modifier.height(Space.x24))
                if (attempt.hasOutcome) {
                    PrimaryButton(
                        text = stringResource(Res.string.onboarding_seed_next),
                        onClick = onNext,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.onboarding_seed_need_take),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(Space.x4))
                QuietTextButton(
                    text = stringResource(Res.string.onboarding_seed_skip),
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.x16))
            }
        }
    }
}

/**
 * A settled seed state: the profile is done, or the bank could not be fetched.
 * One short message, centred in the body so the screen does not read as
 * half-built, with a title of its own so it never repeats the measuring invite
 * of the phrase that is already over.
 */
@Composable
private fun SeedMessage(
    title: String,
    body: String,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.x32, vertical = Space.x24),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.height(Space.x24))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.x12))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Space.x32))
            action()
        }
    }
}

@Composable
private fun HowStep(icon: ImageVector, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.x12),
        verticalAlignment = Alignment.Top,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(Space.x16))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x2))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = if (LocalReduceMotion.current) tween(0) else Motion.pulseDp,
                label = "dotWidth",
            )
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

/**
 * The welcome visual: a row of rounded voice bars, cobalt at the centre and
 * fading to the edges, that rise once from the baseline like a phrase being
 * spoken. It plays once; after that it is still.
 */
@Composable
private fun SpeakingBars(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val rise = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(reduce) {
        if (!reduce) rise.animateTo(1f, animationSpec = tween(1200, easing = Motion.decelerate))
    }
    val color = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val count = 29
        val gap = 5.dp.toPx()
        val barWidth = (size.width - gap * (count - 1)) / count
        val mid = size.height / 2f
        for (i in 0 until count) {
            val t = i / (count - 1f)
            val envelope = kotlin.math.sin(kotlin.math.PI.toFloat() * t)
            val wobble = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(i * 1.7f))
            // Bars rise left to right, each a beat after the one before it.
            val local = ((rise.value * 1.6f) - t * 0.6f).coerceIn(0f, 1f)
            val h = (size.height * envelope * wobble * local).coerceAtLeast(barWidth)
            val emphasis = envelope * envelope
            drawRoundRect(
                color = if (emphasis > 0.35f) color.copy(alpha = 0.45f + 0.55f * emphasis) else quiet,
                topLeft = Offset(i * (barWidth + gap), mid - h / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * The interface language, offered here because the very first screen is where a
 * learner notices it is in the wrong language. Only on Android 13+, where the
 * per-app locale API exists; below that the app follows the system.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLanguageChoice() {
    val actions = rememberPlatformActions()
    var appLocale by remember { mutableStateOf(actions.appLocale()) }

    SectionLabel(stringResource(Res.string.settings_app_language))
    Spacer(Modifier.height(Space.x12))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.x8),
        verticalArrangement = Arrangement.spacedBy(Space.x8),
    ) {
        APP_LOCALE_TAGS.forEach { tag ->
            val active = tag == appLocale
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = Sizes.touch)
                    .clip(RoundedCornerShape(Radius.full))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = {
                            appLocale = tag
                            actions.setAppLocale(tag)
                        },
                    )
                    .padding(horizontal = Space.x16),
            ) {
                Text(
                    text = appLocaleDisplay(tag),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
