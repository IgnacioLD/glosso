package com.glosso.app.billing

import com.glosso.app.api.AccountState
import com.glosso.app.api.ProductConfig
import com.glosso.engine.model.LessonModel

/**
 * The access side of one content item. It is a property of the item, not of the
 * screen that shows it: [FREE] is in the always-free level, [PRO] is behind the
 * subscription. The UI reads this and decides with the current entitlement; it
 * never re-derives the frontier itself.
 */
enum class LessonAccess { FREE, PRO }

/**
 * A Pro surface that is not itself a content item and so has no CEFR level to
 * read. It follows the entitlement directly: either the learner is Pro or the
 * surface asks for the paywall. Named here so a screen never invents its own
 * "is this Pro?" check.
 */
enum class ProFeature {
    /** Practice planned from the learner's phonetic profile. */
    PERSONALIZED_PRACTICE,

    /** The phonetic profile itself. */
    PHONETIC_PROFILE,
}

/**
 * The single place where "what is free and what is paid" is defined.
 *
 * The frontier is the CEFR level, not an arbitrary block inside one: the whole of
 * [FREE_LEVEL] (A1) is free, and from the next level on everything is Pro. That
 * makes the rule easy to state in one sentence - "finish A1 for free, continue
 * with A2 and beyond on Pro" - and impossible to hit mid-level: no learner sees
 * the wall between two lessons of the same level. A lesson that is already in
 * progress is always continuable, even at a paid level, so the access decision
 * happens once, before starting, and never mid-lesson.
 *
 * Offline downloads mirror the same frontier: the free level is downloadable by
 * anyone, the paid levels only by an entitled learner.
 *
 * This is a UX gate, not a security boundary: entitlement is enforced for real by
 * server-side verification of the purchase token (phase 2, see docs/BILLING.md),
 * because a client can always be tampered with. Change the product decision here
 * and nowhere else.
 */
object AccessPolicy {

    /** The only level that is free: the whole of A1 is on us. */
    const val FREE_LEVEL = "A1"

    /** True when [status] should unlock every Pro surface, ignoring the product phase. */
    fun isEntitled(status: SubscriptionStatus): Boolean = status.isEntitled

    /**
     * The access the server grants this device, from `GET /v1/config` and
     * `GET /v1/me`. The client decides nothing here: it reads the phase and the
     * plan the server publishes.
     *
     * While the beta is active the whole course is [ProductAccess.betaFree] and
     * nothing is sold. Selling only turns on once the product has left the beta,
     * the PRO flag is on and a real price has been published; a null price keeps
     * it off, so the app never invents a number.
     */
    fun productAccess(config: ProductConfig, account: AccountState?): ProductAccess {
        val betaFree = config.isBeta || config.beta.active
        val selling = !betaFree &&
            config.flag("pro") &&
            config.pricingOf(ProductPlan.PRO.name)?.published == true
        val betaFounder = account?.betaFounder == true
        val grant = when {
            betaFounder -> EntitlementGrant(
                plan = ProductPlan.PRO,
                kind = EntitlementKind.LIFETIME,
                source = "beta_founder",
                label = account.betaFounderBenefit ?: config.beta.founderBenefit?.label ?: "Beta Founder",
            )
            else -> null
        }
        return ProductAccess(
            betaFree = betaFree,
            plan = ProductPlan.fromWire(account?.plan),
            sellingEnabled = selling,
            betaFounder = betaFounder,
            lifetimeGrant = grant,
        )
    }

    /** The version policy from the config, for a soft, non-blocking notice. */
    fun versionNotice(config: ProductConfig, currentVersion: Int): VersionNotice =
        VersionNotice(
            currentVersion = currentVersion,
            recommendedVersion = config.client.recommendedVersion,
            minimumSupportedVersion = config.client.minimumSupportedVersion,
        )

    /**
     * True when [status] unlocks every Pro content surface, including the open
     * beta and a Beta Founder: both reach the PRO content tier whatever the
     * local Play state says. Personalization is separate, see [isPersonalized].
     */
    fun isEntitled(status: SubscriptionStatus, access: ProductAccess): Boolean =
        access.entitled || status.isEntitled

    /**
     * True when [status] unlocks the personalization tier. This is the top plan,
     * not the PRO content tier: the open beta opens it, a local Play subscription
     * opens it, and a server-granted PERSONALIZED plan opens it. A Beta Founder
     * is granted PRO, never PERSONALIZED, so it does not open it.
     */
    fun isPersonalized(status: SubscriptionStatus, access: ProductAccess): Boolean =
        access.personalized || status.isEntitled

    /**
     * The theme order the course is taught in: the manifest order first, then any
     * theme the manifest did not list, in the order it first appears. Used to sort
     * the lesson list, never to decide what is free.
     */
    fun blockOrder(lessons: List<LessonModel>, themeOrder: List<String> = emptyList()): List<String> {
        val present = lessons.asSequence()
            .map { it.theme }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val known = themeOrder.filter { it in present }
        return known + present.filterNot { it in known }
    }

    /**
     * The order of the lessons inside one block: by level, then by the kind of
     * base (a dialogue before a story before an article), then by id. The lesson
     * list and the "what is next" decision share it, so the hero can never offer
     * a lesson that is not the next row of the list.
     */
    val lessonOrder: Comparator<LessonModel> = compareBy({ it.level }, { baseRank(it) }, { it.id })

    private fun baseRank(lesson: LessonModel): Int = when {
        lesson.dialogueId != null -> 0
        lesson.storyId != null -> 1
        lesson.articleId != null -> 2
        else -> 3
    }

    /** Every lesson in the order the course is taught: block by block, then [lessonOrder]. */
    fun teachingOrder(lessons: List<LessonModel>, themeOrder: List<String> = emptyList()): List<LessonModel> {
        val byTheme = lessons.groupBy { it.theme }
        return blockOrder(lessons, themeOrder).flatMap { byTheme[it].orEmpty().sortedWith(lessonOrder) }
    }

    /**
     * The first lesson in teaching order: the one a new learner should meet. Null
     * only when there is no lesson at all.
     */
    fun firstLesson(lessons: List<LessonModel>, themeOrder: List<String> = emptyList()): LessonModel? =
        teachingOrder(lessons, themeOrder).firstOrNull()

    /**
     * The lesson to offer when none is in progress: the first one in teaching
     * order the learner has not finished yet. Once every lesson is finished there
     * is nothing new to offer, so it falls back to the first one rather than to
     * an empty card.
     */
    fun nextLesson(
        lessons: List<LessonModel>,
        themeOrder: List<String> = emptyList(),
        finishedLessonIds: Collection<String> = emptyList(),
    ): LessonModel? {
        val ordered = teachingOrder(lessons, themeOrder)
        return ordered.firstOrNull { it.id !in finishedLessonIds } ?: ordered.firstOrNull()
    }

    /**
     * The access side of any content level, independent of any subscription. Every
     * content item (lesson, story, exercise, drill) carries its CEFR level, so the
     * frontier reads the same for all of them: A1 is free, the rest is Pro.
     */
    fun accessOf(level: String): LessonAccess =
        if (level == FREE_LEVEL) LessonAccess.FREE else LessonAccess.PRO

    /** True when content at [level] is inside the free level. */
    fun isLevelFree(level: String): Boolean = accessOf(level) == LessonAccess.FREE

    /** The access side of [lesson], independent of any subscription. */
    fun accessOf(lesson: LessonModel): LessonAccess = accessOf(lesson.level)

    /** True when [lesson] is inside the free level. */
    fun isLessonFree(lesson: LessonModel): Boolean = isLevelFree(lesson.level)

    /**
     * True when [status] unlocks [feature]. The one place a feature-level (not
     * item-level) decision is made, so screens never test the entitlement
     * themselves. The PRO content tier and the personalization tier are not the
     * same: the phonetic profile is PRO analytics, the personalized practice is
     * the top PERSONALIZED plan.
     */
    fun isFeatureUnlocked(
        status: SubscriptionStatus,
        feature: ProFeature,
        access: ProductAccess = ProductAccess.Unknown,
    ): Boolean =
        when (feature) {
            ProFeature.PERSONALIZED_PRACTICE -> isPersonalized(status, access)
            ProFeature.PHONETIC_PROFILE -> isEntitled(status, access)
        }

    /**
     * True when [lesson] has been opened and must be finishable regardless of the
     * entitlement: a learner who started a paid lesson before the trial lapsed
     * keeps the right to finish it.
     */
    fun isLessonInProgress(lesson: LessonModel, inProgressLessonId: String?): Boolean =
        inProgressLessonId != null && lesson.id == inProgressLessonId

    /**
     * True when [lesson] can be opened and finished. An in-progress lesson is
     * always continuable, so the access decision happens once, before starting.
     */
    fun isLessonUnlocked(
        lesson: LessonModel,
        status: SubscriptionStatus,
        inProgressLessonId: String? = null,
        access: ProductAccess = ProductAccess.Unknown,
    ): Boolean =
        isEntitled(status, access) ||
            isLessonFree(lesson) ||
            isLessonInProgress(lesson, inProgressLessonId)

    /** True when opening [lesson] must show the paywall instead of the lesson. */
    fun requiresPaywall(
        lesson: LessonModel,
        status: SubscriptionStatus,
        inProgressLessonId: String? = null,
        access: ProductAccess = ProductAccess.Unknown,
    ): Boolean = !isLessonUnlocked(lesson, status, inProgressLessonId, access)

    /**
     * Offline downloads mirror the content frontier instead of gating the whole
     * feature: an entitled learner may download any lesson, a free learner may
     * download the lessons in the free level. Downloading never assumes the
     * learner is Pro.
     */
    fun canDownloadOffline(
        lesson: LessonModel,
        status: SubscriptionStatus,
        access: ProductAccess = ProductAccess.Unknown,
    ): Boolean =
        isEntitled(status, access) || isLessonFree(lesson)
}
