package com.glosso.app

import com.glosso.app.platform.currentLocaleTag
import com.glosso.app.platform.currentPlatformContext
import com.glosso.app.platform.formatWeekdayDayMonth
import com.glosso.app.platform.todayEpochDay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.glosso.app.billing.TodayPlan
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.phonetics.PhoneticProfile
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How many weak sounds Today offers to review; the planner caps the same way. */
private const val WEAK_LIMIT = 3

/**
 * The home. One clear action (continue the lesson), the learner's weak sounds
 * ready to review, and the honest daily streak. Nothing here counts XP or
 * pretends progress: the streak is days with a real scored take, and the review
 * is exactly what the phonetic profile says is weakest.
 */
@Composable
fun TodayScreen(
    plan: TodayPlan,
    loading: Boolean,
    step: LessonStep? = null,
    hasLessons: Boolean,
    onStartLesson: () -> Unit,
    onBrowseLessons: () -> Unit,
    onReviewSounds: () -> Unit,
    onSettings: () -> Unit,
    soundsLocked: Boolean = false,
    onUnlockSounds: () -> Unit = {},
    offline: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    profileVm: ProfileViewModel = profileViewModel(),
) {
    val profile by profileVm.profile.collectAsState()
    val progress by ProgressStore.state.collectAsState()
    val reminderEnabled by DailyReminder.enabled.collectAsState()
    val context = currentPlatformContext()
    LaunchedEffect(Unit) { DailyReminder.ensureInitialized(context) }

    val weak = profile?.let { PhoneticProfile.weakest(it, limit = WEAK_LIMIT) }.orEmpty()
    val hasProfile = !profile?.stats.isNullOrEmpty()

    val scroll = rememberScrollState()
    val collapse = rememberCollapse(scroll)
    val locale = currentLocaleTag()
    val dateLine = remember(locale) { todayLine(todayEpochDay(), locale) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopHeader(
            title = stringResource(Res.string.today_title),
            eyebrow = dateLine,
            collapse = { collapse.value },
            trailing = { StreakChip(days = progress.shownDailyStreak()) },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = Space.gutter),
        ) {
            Spacer(Modifier.height(Space.x20))
            StaggeredAppear(index = 0) {
                if (!loading && plan.lesson == null && error != null) {
                    ErrorState(
                        onRetry = onRetry,
                        detail = error,
                        title = if (offline) stringResource(Res.string.offline_banner) else null,
                    )
                } else {
                    LessonCard(
                        lesson = plan.lesson,
                        loading = loading,
                        canResume = plan.resumable,
                        step = step,
                        locked = plan.locked,
                        hasLessons = hasLessons,
                        onStart = onStartLesson,
                        onBrowse = onBrowseLessons,
                    )
                }
            }

            Spacer(Modifier.height(Space.x40))
            StaggeredAppear(index = 1) {
                Column {
                    SectionLabel(stringResource(Res.string.today_sounds))
                    Spacer(Modifier.height(Space.x16))
                    SoundsSection(
                        weak = weak,
                        hasProfile = hasProfile,
                        locked = soundsLocked,
                        onReview = onReviewSounds,
                        onUnlock = onUnlockSounds,
                    )
                }
            }

            if (reminderEnabled) {
                val days = daysSinceLastActive(progress.lastTakeDay, todayEpochDay())
                if (days != null && days >= REMINDER_AFTER_DAYS) {
                    Spacer(Modifier.height(Space.x32))
                    ReminderCard(days = days, onStart = onStartLesson)
                }
            }

            Spacer(Modifier.height(Space.x40))
            StaggeredAppear(index = 2) {
                Column {
                    SectionLabel(stringResource(Res.string.lessons_week))
                    Spacer(Modifier.height(Space.x16))
                    WeekDots(progress)
                }
            }

            Spacer(Modifier.height(Space.x32))
            QuietTextButton(
                text = stringResource(Res.string.today_settings),
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.x24))
        }
    }
}

@Composable
private fun LessonCard(
    lesson: LessonModel?,
    loading: Boolean,
    canResume: Boolean,
    step: LessonStep?,
    locked: Boolean,
    hasLessons: Boolean,
    onStart: () -> Unit,
    onBrowse: () -> Unit,
) {
    LessonHeroSurface(
        kicker = stringResource(Res.string.today_lesson),
        tags = if (lesson != null) {
            { GlossoTag(if (locked) "Pro" else baseLabel(lesson)) }
        } else {
            null
        },
    ) {
        when {
            loading -> {
                // The skeleton is shape only; TalkBack hears that the lesson is loading.
                val loadingLabel = stringResource(Res.string.today_lesson_loading)
                Column(Modifier.clearAndSetSemantics { contentDescription = loadingLabel }) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(30.dp))
                    Spacer(Modifier.height(Space.x12))
                    SkeletonBlock(Modifier.fillMaxWidth().height(16.dp))
                    Spacer(Modifier.height(Space.x8))
                    SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(16.dp))
                    Spacer(Modifier.height(Space.x24))
                    SkeletonBlock(Modifier.fillMaxWidth().height(Sizes.button))
                }
            }

            lesson == null -> {
                Text(
                    text = stringResource(Res.string.today_lesson_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.x20))
                PrimaryButton(stringResource(Res.string.today_browse), onClick = onBrowse)
            }

            else -> {
                LessonHeroTitle(lesson.title, lesson.objective)
                if (canResume && step != null) {
                    Spacer(Modifier.height(Space.x20))
                    LessonStepProgress(lessonSteps(lesson), step)
                }
                Spacer(Modifier.height(Space.x24))
                PrimaryButton(
                    text = stringResource(
                        when {
                            locked -> Res.string.lessons_unlock_with_pro
                            canResume -> Res.string.today_continue
                            else -> Res.string.today_start
                        },
                    ),
                    onClick = onStart,
                )
                if (hasLessons) {
                    Spacer(Modifier.height(Space.x4))
                    TextButton(
                        onClick = onBrowse,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(Res.string.today_browse), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(Space.x4))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(Sizes.iconSmall),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The weak-sound review. It is a quiet detail under the one lesson card, not a
 * second card competing for the eye: the home has a single primary action, so
 * this section never carries a gold button and never repeats "Start".
 */
@Composable
private fun SoundsSection(
    weak: List<PhonemeStatModel>,
    hasProfile: Boolean,
    locked: Boolean,
    onReview: () -> Unit,
    onUnlock: () -> Unit,
) {
    val showPhonetics by PhoneticsPreferences.visible.collectAsState()
    // The weak sounds are the phonetic profile, which is Pro. A free learner sees
    // the same wall the profile shows, kept quiet so it does not compete with the
    // lesson action.
    when {
        locked -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Sizes.icon),
            )
            Spacer(Modifier.width(Space.x12))
            Text(
                text = stringResource(Res.string.today_sounds_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.x8))
            QuietTextButton(stringResource(Res.string.today_sounds_unlock), onClick = onUnlock)
        }

        !hasProfile -> Text(
            text = stringResource(Res.string.today_sounds_no_profile),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        weak.isEmpty() -> Text(
            text = stringResource(Res.string.today_sounds_clear),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Column {
            if (showPhonetics) {
                Text(
                    text = stringResource(Res.string.today_sounds_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x20))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.x12),
                ) {
                    // Read-only dials: the one action is the review button below,
                    // so the row is not a second set of targets doing the same thing.
                    weak.forEach { stat ->
                        SoundDial(
                            phoneme = "/${stat.phoneme}/",
                            accuracy = stat.accuracy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep dials the same width whether there are one, two or three.
                    repeat(WEAK_LIMIT - weak.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(Space.x20))
            }
            SecondaryButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                Text(
                    pluralStringResource(Res.plurals.today_review, weak.size, weak.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ReminderCard(days: Int, onStart: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Radius.l),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            Text(
                text = stringResource(Res.string.today_reminder_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(Res.string.today_reminder_body, days),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x16))
            PrimaryButton(stringResource(Res.string.today_start), onClick = onStart)
        }
    }
}

/**
 * Today as the header's kicker, in the learner's own date order ("viernes, 25
 * de septiembre", "Friday, September 25"): the platform picks the pattern for
 * the locale instead of one hard-coded order for four languages.
 */
internal fun todayLine(epochDay: Long, languageTag: String): String = formatWeekdayDayMonth(epochDay, languageTag)
