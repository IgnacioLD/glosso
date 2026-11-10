package com.glosso.app

import com.glosso.app.platform.formatDayShortMonth
import com.glosso.app.platform.isoDayOfWeek
import com.glosso.app.platform.todayEpochDay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glosso.app.billing.AccessController
import com.glosso.app.billing.ProFeature
import com.glosso.app.billing.ProductAccess
import com.glosso.app.billing.SubscriptionStatus
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.GlossoType
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Space
import com.glosso.app.product.ProductConfigRepository
import com.glosso.app.resources.*
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.phonetics.ErrorRules
import com.glosso.engine.phonetics.PhoneticLoop
import com.glosso.engine.phonetics.PhoneticProfile
import com.glosso.engine.profile.CompetenceSignal
import com.glosso.engine.profile.DailyPracticePlan
import com.glosso.engine.profile.ErrorPatternDetector
import com.glosso.engine.profile.PhonemeMemory
import com.glosso.engine.profile.PhonemePattern
import com.glosso.engine.profile.PhonemeTrend
import com.glosso.engine.profile.PhonemeTrends
import com.glosso.engine.profile.PracticeItem
import com.glosso.engine.profile.PracticeKind
import com.glosso.engine.profile.PracticeReason
import com.glosso.engine.profile.PracticeRecommender
import com.glosso.engine.profile.SkillDimension
import com.glosso.engine.profile.SkillProfile
import com.glosso.engine.profile.SkillScore
import com.glosso.engine.profile.TrendDirection
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One destination for the learner: the answer to "am I pronouncing better?"
 * The profile leads (a big number, the week's change, then the sounds as bars
 * ordered by weakness), with today's practice, the trends and patterns behind
 * the profile and the dimensions being built, and the effort underneath (days,
 * rhythm, achievements) as context rather than as the headline.
 */
@Composable
fun YouScreen(
    vm: ProfileViewModel = profileViewModel(),
    subscription: SubscriptionStatus = SubscriptionStatus.NONE,
    productAccess: ProductAccess = ProductAccess.Unknown,
    onShowPaywall: (String?) -> Unit = {},
    onPractice: () -> Unit = {},
) {
    // The phonetic profile is Pro. A free learner gets the same locked card as
    // every other wall; the data itself is never rendered.
    val access = remember(subscription, productAccess) { AccessController(subscription, productAccess = productAccess) }
    if (access.isLocked(ProFeature.PHONETIC_PROFILE)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopHeader(
                title = stringResource(Res.string.you_title),
            )
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = Space.gutter),
                contentAlignment = Alignment.Center,
            ) {
                ProLockedCard(
                    title = stringResource(Res.string.pro_locked_title),
                    message = stringResource(Res.string.pro_locked_message),
                    onUnlock = { onShowPaywall(null) },
                )
            }
        }
        return
    }

    val profile by vm.profile.collectAsState()
    val history by vm.history.collectAsState()
    val progress by ProgressStore.state.collectAsState()
    val baselines by PhonemeHistory.baselines.collectAsState()
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    val betaConfig by ProductConfigRepository.config.collectAsState()
    val betaAccount by ProductConfigRepository.account.collectAsState()
    val baseline = baselines[LanguagePreferences.current.code]
    val today = todayEpochDay()
    val stats = profile?.stats.orEmpty().sortedWith(compareBy({ it.accuracy }, { it.phoneme }))
    val overall = PhoneticLoop.overall(profile)
    val weekDelta = baseline?.let { overall - it.overall }
    val focus = stats.firstOrNull {
        it.attempts >= PhoneticProfile.MIN_ATTEMPTS && it.accuracy < 100
    }

    val plan = remember(profile, history, today) {
        profile?.let { PracticeRecommender.plan(it, history, today) } ?: DailyPracticePlan()
    }
    val patterns = remember(history) { ErrorPatternDetector.persistent(history) }
    val trends = remember(history) { PhonemeTrends.all(history) }
    // How much each sound moved today, so the sound list notes the day's progress
    // instead of only the running number. Null for a sound not measured today.
    val todayDeltas = remember(history, stats, today) {
        stats.associate { it.phoneme to PhonemeTrends.todayDelta(history, it.phoneme, today) }
    }
    val skills = remember(profile, progress, trends) {
        buildSkills(stats, trends, progress)
    }
    // The sound and phrase facts behind the seals. [progress] and [today] are
    // keys so the count is reread after every take and the daily seals roll
    // over when the screen is left open across a day change.
    val signals = remember(profile, history, progress, today) {
        AchievementCatalog.takeSignals(profile, history).copy(today = today)
    }
    // Record any seal the live signals already earn, so a seal reached elsewhere
    // or before this build is not left showing full progress while still locked.
    LaunchedEffect(signals) { ProgressStore.reconcile(signals) }

    val listState = rememberLazyListState()
    val collapse = rememberCollapse(listState)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopHeader(
            title = stringResource(Res.string.you_title),
            collapse = { collapse.value },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.x40),
        ) {
            item {
                Spacer(Modifier.height(Space.x20))
                SectionLabel(stringResource(Res.string.you_pronunciation))
                Spacer(Modifier.height(Space.x12))
            }

            if (stats.isEmpty()) {
                item { EmptyProfileCard(onPractice) }
            } else {
                item {
                    PronunciationPanel(
                        overall = overall,
                        weekDelta = weekDelta,
                        baseline = baseline,
                        stats = stats,
                    )
                    val attempts = stats.sumOf { it.attempts }
                    val correct = stats.sumOf { it.correct }
                    val global = if (attempts == 0) 0 else correct * 100 / attempts
                    val strong = stats.count { it.accuracy == 100 }
                    val strongLabel = pluralStringResource(Res.plurals.you_sounds_mastered, strong, strong)
                    Spacer(Modifier.height(Space.x16))
                    Text(
                        stringResource(Res.string.you_global, global, strongLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    focus?.let {
                        Spacer(Modifier.height(Space.x12))
                        FocusNote(it)
                        val trend = trends.firstOrNull { t -> t.phoneme == it.phoneme }
                        if (trend != null && trend.days >= 2) {
                            Spacer(Modifier.height(Space.x12))
                            SoundTrendCard(trend)
                        }
                    }
                    patterns.take(2).forEach { pattern ->
                        Spacer(Modifier.height(Space.x12))
                        PatternNote(pattern)
                    }
                    Spacer(Modifier.height(Space.x32))
                    if (showPhonetics) {
                        SectionLabel(stringResource(Res.string.you_your_sounds))
                        Spacer(Modifier.height(Space.x4))
                        Text(
                            stringResource(Res.string.you_sounds_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Space.x12))
                        stats.take(MAX_SOUND_BARS).forEach { stat ->
                            SoundBarRow(stat, todayDeltas[stat.phoneme])
                            Spacer(Modifier.height(Space.x12))
                        }
                        Spacer(Modifier.height(Space.x20))
                    }
                    focus?.let {
                        SectionLabel(stringResource(Res.string.you_work_on))
                        Spacer(Modifier.height(Space.x12))
                        WorkOnCard(it, onPractice)
                    }
                    Spacer(Modifier.height(Space.x32))
                }
            }

            if (!plan.isEmpty) {
                item {
                    SectionLabel(stringResource(Res.string.you_today_practice))
                    Spacer(Modifier.height(Space.x12))
                    TodayPlanCard(plan, onPractice)
                    Spacer(Modifier.height(Space.x24))
                }
            }

            item {
                BigStatRow(
                    activeDays = progress.activeDays,
                    streak = progress.shownDailyStreak(),
                    sounds = stats.size,
                )
                Spacer(Modifier.height(Space.x24))
            }

            if (betaConfig.isBeta || betaConfig.beta.active) {
                item {
                    BetaBanner(config = betaConfig, account = betaAccount)
                    Spacer(Modifier.height(Space.x24))
                }
            }

            if (skills.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(Res.string.you_competences))
                    Spacer(Modifier.height(Space.x12))
                    SkillsCard(skills)
                    Spacer(Modifier.height(Space.x24))
                }
            }

            item {
                SectionLabel(stringResource(Res.string.you_last_30_days))
                Spacer(Modifier.height(Space.x12))
                ActivityChart(progress)
                Spacer(Modifier.height(Space.x32))
                SectionLabel(stringResource(Res.string.you_calendar))
                Spacer(Modifier.height(Space.x12))
                PracticeCalendar(progress)
                Spacer(Modifier.height(Space.x32))
                SectionLabel(stringResource(Res.string.you_achievements))
                Spacer(Modifier.height(Space.x12))
                AchievementsGrid(progress, signals)
            }
        }
    }
}

private const val MAX_SOUND_BARS = 8

/** Pronunciation as a headline: the number, the week's change, and what moved. */
@Composable
private fun PronunciationPanel(
    overall: Int,
    weekDelta: Int?,
    baseline: WeeklyBaseline?,
    stats: List<PhonemeStatModel>,
) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.background(heroWash()).padding(Space.x20)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(score = overall, size = 124.dp)
                Spacer(Modifier.width(Space.x20))
                weekDelta?.let { delta ->
                    Text(
                        text = when {
                            delta > 0 -> stringResource(Res.string.you_week_up, delta)
                            delta < 0 -> stringResource(Res.string.you_week_down, delta)
                            else -> stringResource(Res.string.you_week_flat)
                        },
                        // A gain is the headline; a dip is stated plainly, not in
                        // alarm red: a weaker week is information, not a verdict.
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        color = if (delta > 0) GlossoColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val moved = stats.mapNotNull { stat ->
                val from = PhonemeHistory.baselineSound(baseline, stat.phoneme)
                if (from != null && from != stat.accuracy) Triple(stat.phoneme, from, stat.accuracy) else null
            }.sortedByDescending { it.third - it.second }.take(3)
            if (showPhonetics && moved.isNotEmpty()) {
                Spacer(Modifier.height(Space.x16))
                moved.forEach { (phoneme, from, to) ->
                    Text(
                        text = stringResource(Res.string.you_sound_delta, phoneme, from, to),
                        style = GlossoType.MonoIPA,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.x4))
                }
            }
        }
    }
}

/** One sound as a bar and a number, plus how much it moved today, if at all. */
@Composable
private fun SoundBarRow(stat: PhonemeStatModel, todayDelta: Int?) {
    val color = accuracyColor(stat.accuracy)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "/${stat.phoneme}/",
            style = GlossoType.MonoIPA,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 52.dp),
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(Radius.full))
                .background(MaterialTheme.colorScheme.outline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((stat.accuracy / 100f).coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(Radius.full))
                    .background(color),
            )
        }
        Spacer(Modifier.width(Space.x12))
        Text(
            text = stringResource(Res.string.label_percent, stat.accuracy),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.widthIn(min = 44.dp),
            maxLines = 1,
        )
        TodayDeltaBadge(todayDelta)
    }
}

/**
 * How far a sound moved today, as a compact coloured delta. A day without a new
 * measurement, or one that moved nothing, keeps the column empty so the list stays
 * quiet instead of full of zeroes.
 */
@Composable
private fun TodayDeltaBadge(delta: Int?) {
    val width = 52.dp
    if (delta == null || delta == 0) {
        Spacer(Modifier.width(width))
        return
    }
    val up = delta > 0
    val color = if (up) GlossoColors.success else GlossoColors.danger
    Row(
        modifier = Modifier.width(width),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (up) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
            contentDescription = stringResource(
                if (up) Res.string.you_today_up else Res.string.you_today_down,
                abs(delta),
            ),
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Space.x2))
        Text(
            text = "${abs(delta)}%",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

/** The sound to work on next, with its minimal pair and one tap to practise it. */
@Composable
private fun WorkOnCard(stat: PhonemeStatModel, onPractice: () -> Unit) {
    val pair = ErrorRules.ruleFor(stat.phoneme, stat.typicalError)?.examples.orEmpty()
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(Radius.l),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            if (showPhonetics) {
                Text(
                    text = "/${stat.phoneme}/",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (pair.isNotEmpty()) {
                Spacer(Modifier.height(Space.x8))
                ExampleWords(pair.take(3))
            }
            Spacer(Modifier.height(Space.x16))
            PrimaryButton(
                text = if (showPhonetics) {
                    stringResource(Res.string.you_practise_sound, stat.phoneme)
                } else {
                    stringResource(Res.string.you_practise_sound_plain)
                },
                onClick = onPractice,
            )
        }
    }
}

// --- Today's practice -------------------------------------------------------

@Composable
private fun TodayPlanCard(plan: DailyPracticePlan, onPractice: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.you_today_minutes, plan.totalMinutes),
                    style = GlossoType.Figure,
                    color = GlossoColors.accentText,
                )
            }
            plan.items.forEach { item ->
                Spacer(Modifier.height(Space.x12))
                PracticeRow(item)
            }
            Spacer(Modifier.height(Space.x20))
            PrimaryButton(stringResource(Res.string.you_plan_start), onClick = onPractice)
        }
    }
}

@Composable
private fun PracticeRow(item: PracticeItem) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label and its reason take the room that is left once the minutes
        // have theirs, and the reason is the only part allowed to shorten. That
        // way the trailing "1 min" never wraps onto a second line on a narrow
        // phone.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item.kind) {
                PracticeKind.SOUND -> Text(
                    text = if (showPhonetics) {
                        item.phoneme.orEmpty()
                    } else {
                        stringResource(Res.string.you_plan_sound)
                    },
                    style = if (showPhonetics) GlossoType.MonoIPA else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )

                PracticeKind.PHRASES -> Text(
                    stringResource(Res.string.you_plan_phrases),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            // "Phrases in context" already says "put it into phrases"; the sound
            // rows are the ones that need the reason.
            if (item.kind == PracticeKind.SOUND) {
                Spacer(Modifier.width(Space.x12))
                Text(
                    reasonLabel(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(Space.x12))
        Text(
            stringResource(Res.string.you_plan_minutes, item.minutes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun reasonLabel(item: PracticeItem): String = when (item.kind) {
    PracticeKind.PHRASES -> stringResource(Res.string.you_reason_phrases)
    PracticeKind.SOUND -> when (item.reason) {
        PracticeReason.PERSISTENT -> stringResource(Res.string.you_reason_persistent)
        PracticeReason.LAPSED -> stringResource(Res.string.you_reason_lapsed)
        PracticeReason.WEAK -> stringResource(Res.string.you_reason_weak)
        PracticeReason.CHECK_IN -> stringResource(Res.string.you_reason_check_in)
    }
}

// --- Trends and patterns ----------------------------------------------------

@Composable
private fun SoundTrendCard(trend: PhonemeTrend) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showPhonetics) {
                        stringResource(Res.string.you_trend_title, trend.phoneme)
                    } else {
                        stringResource(Res.string.you_trend_title_plain)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(Res.string.label_percent, trend.latestAccuracy),
                    style = GlossoType.Figure,
                    color = accuracyColor(trend.latestAccuracy),
                )
            }
            Spacer(Modifier.height(Space.x20))
            DayBars(trend)
            Spacer(Modifier.height(Space.x12))
            Text(
                trendSummary(trend),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayBars(trend: PhonemeTrend) {
    val first = trend.points.firstOrNull()?.day ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().height(84.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.x8),
        verticalAlignment = Alignment.Bottom,
    ) {
        trend.points.forEach { point ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    "${point.accuracy}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.x4))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((6 + point.accuracy * 40 / 100).dp)
                        .clip(RoundedCornerShape(Radius.xs))
                        .background(accuracyColor(point.accuracy)),
                )
                Spacer(Modifier.height(Space.x4))
                Text(
                    stringResource(Res.string.you_trend_day, (point.day - first + 1).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun trendSummary(trend: PhonemeTrend): String = when (trend.direction) {
    TrendDirection.IMPROVING ->
        stringResource(Res.string.you_trend_up, trend.delta)
    TrendDirection.DECLINING ->
        stringResource(Res.string.you_trend_down, trend.delta)
    TrendDirection.STABLE, TrendDirection.UNKNOWN ->
        stringResource(Res.string.you_trend_flat, trend.latestAccuracy)
}

@Composable
private fun PatternNote(pattern: PhonemePattern) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(Radius.m),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Space.x16, vertical = Space.x12)) {
            Text(
                if (showPhonetics) {
                    stringResource(Res.string.you_pattern_title, pattern.phoneme)
                } else {
                    stringResource(Res.string.you_pattern_title_plain)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(Space.x4))
            Text(
                when {
                    pattern.error == PhonemeMemory.DROPPED ->
                        stringResource(Res.string.you_pattern_dropped, (pattern.confidence * 100).roundToInt())
                    showPhonetics ->
                        stringResource(Res.string.you_pattern_detail, pattern.error, (pattern.confidence * 100).roundToInt())
                    else ->
                        stringResource(Res.string.you_pattern_detail_plain, (pattern.confidence * 100).roundToInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// --- Multidimensional profile ----------------------------------------------

@Composable
private fun SkillsCard(skills: List<SkillScore>) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            skills.forEachIndexed { index, skill ->
                if (index > 0) Spacer(Modifier.height(Space.x16))
                SkillRow(skill)
            }
        }
    }
}

@Composable
private fun SkillRow(skill: SkillScore) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                skillLabel(skill.dimension),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Space.x8))
            Text(
                stringResource(Res.string.label_percent, skill.score),
                style = GlossoType.Figure,
                color = accuracyColor(skill.score),
            )
        }
        Spacer(Modifier.height(Space.x8))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(Radius.xs))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((skill.score / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(accuracyColor(skill.score)),
            )
        }
    }
}

@Composable
private fun skillLabel(dimension: SkillDimension): String = when (dimension) {
    SkillDimension.PRONUNCIATION -> stringResource(Res.string.skill_pronunciation)
    SkillDimension.COMPREHENSION -> stringResource(Res.string.skill_comprehension)
    SkillDimension.VOCABULARY -> stringResource(Res.string.skill_vocabulary)
    SkillDimension.GRAMMAR -> stringResource(Res.string.skill_grammar)
}

private fun buildSkills(
    stats: List<PhonemeStatModel>,
    trends: List<PhonemeTrend>,
    progress: ProgressData,
): List<SkillScore> {
    val attempts = stats.sumOf { it.attempts }
    val correct = stats.sumOf { it.correct }
    val pronunciation = if (attempts > 0) {
        SkillScore(
            dimension = SkillDimension.PRONUNCIATION,
            score = correct * 100 / attempts,
            sample = attempts,
            improving = trends.any { it.direction == TrendDirection.IMPROVING },
        )
    } else {
        null
    }
    val competencies = progress.skills.mapNotNull { (key, tally) ->
        val dimension = runCatching { SkillDimension.valueOf(key) }.getOrNull()
            ?: return@mapNotNull null
        dimension to CompetenceSignal(tally.correct, tally.total)
    }.toMap()
    return SkillProfile.build(pronunciation, competencies)
}

// --- Existing building blocks ----------------------------------------------

@Composable
private fun BigStatRow(activeDays: Int, streak: Int, sounds: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(vertical = Space.x20)) {
            BigStat(stringResource(Res.string.you_days), "$activeDays", Modifier.weight(1f))
            BigStat(stringResource(Res.string.you_streak), "$streak", Modifier.weight(1f))
            BigStat(stringResource(Res.string.you_sounds), "$sounds", Modifier.weight(1f))
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = GlossoType.NumberXL,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.x4))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyProfileCard(onPractice: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Space.x20),
            horizontalAlignment = Alignment.Start,
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(Space.x12))
            Text(stringResource(Res.string.you_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.x4))
            Text(
                stringResource(Res.string.you_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x20))
            PrimaryButton(stringResource(Res.string.you_start_practice), onClick = onPractice)
        }
    }
}

@Composable
private fun FocusNote(stat: PhonemeStatModel) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(Radius.m),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.x16, vertical = Space.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showPhonetics) {
                Text(
                    stat.phoneme,
                    style = GlossoType.MonoIPA,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(Space.x12))
            }
            Text(
                stringResource(Res.string.you_focus, stat.accuracy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ActivityChart(data: ProgressData) {
    val today = todayEpochDay()
    val values = (29 downTo 0).map { offset ->
        data.days[(today - offset).toString()] ?: 0
    }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.x4),
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEachIndexed { index, value ->
                val isToday = index == values.lastIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.xs)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (value > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((value.toFloat() / max).coerceIn(0.08f, 1f))
                                .clip(RoundedCornerShape(Radius.xs))
                                .background(
                                    if (isToday) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                ),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(Radius.xs))
                                .background(MaterialTheme.colorScheme.outline),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.x8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDayShortMonth(today - 29),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.you_today),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PracticeCalendar(data: ProgressData) {
    val weeks = 15
    val today = todayEpochDay()
    val thisMonday = today - (isoDayOfWeek(today) - 1)
    val firstMonday = thisMonday - (weeks - 1) * 7L

    // Columns and cells size themselves from the available width, so the grid
    // never overflows the gutter on a narrow phone.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x4)) {
        for (w in 0 until weeks) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.x4),
            ) {
                for (d in 0 until 7) {
                    val date = firstMonday + w * 7L + d
                    val count = data.days[date.toString()] ?: 0
                    val future = date > today
                    val color = when {
                        future -> MaterialTheme.colorScheme.surface
                        count == 0 -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.primary.copy(
                            alpha = (0.35f + 0.16f * count).coerceAtMost(1f),
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(Radius.xs))
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
private fun AchievementsGrid(data: ProgressData, signals: AchievementSignals) {
    // Two fixed columns rather than a wrapping flow: a long title in one language
    // must not push its seal onto a row of its own while the rest stay paired.
    Column(verticalArrangement = Arrangement.spacedBy(Space.x12)) {
        AchievementCatalog.all.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x12)) {
                pair.forEach { achievement ->
                    AchievementSeal(
                        title = stringResource(achievement.titleRes),
                        detail = stringResource(achievement.detailRes),
                        // An open seal shows its full target, so a value that has
                        // since dipped never paints a lit seal with an empty bar.
                        progress = achievement.displayProgress(data, signals),
                        target = achievement.target,
                        // Earned now, or recorded as earned before a later dip.
                        // The reconcile above persists the first kind next frame.
                        unlocked = achievement.isUnlocked(data, signals),
                        modifier = Modifier.weight(1f),
                    )
                }
                // An odd count leaves the last seal alone; keep it half width.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
