package com.glosso.app.billing

import com.glosso.engine.model.ContentMeta
import com.glosso.engine.model.LessonModel

/**
 * The one object the UI asks about access. It binds the current entitlement and
 * the in-progress lesson into per-item answers, so a screen never re-derives the
 * free frontier or threads the raw subscription status through every check.
 *
 * It answers for every content kind (lessons, stories, exercises, drills) through
 * [ContentMeta], because they all carry the CEFR level the frontier reads, and for
 * the feature surfaces that have no level ([ProFeature]). A screen that opens
 * content asks this object; nothing else decides.
 *
 * It is a pure value of the current state: rebuild it when the entitlement or the
 * in-progress lesson changes. [AccessPolicy] stays the only place the rules live;
 * this only holds the state the rules read.
 */
class AccessController(
    private val status: SubscriptionStatus,
    private val inProgressLessonId: String? = null,
    private val productAccess: ProductAccess = ProductAccess.Unknown,
) {

    /** True while the learner is treated as Pro. */
    val entitled: Boolean = AccessPolicy.isEntitled(status, productAccess)

    /** The access side of any content item, independent of the subscription. */
    fun accessOf(item: ContentMeta): LessonAccess = AccessPolicy.accessOf(item.level)

    /** The access side of [lesson], independent of the subscription. */
    fun accessOf(lesson: LessonModel): LessonAccess = AccessPolicy.accessOf(lesson.level)

    /**
     * True when opening [item] must show the paywall instead of the content. A
     * free learner only reaches A1; an entitled learner reaches everything.
     */
    fun isLocked(item: ContentMeta): Boolean =
        !entitled && accessOf(item) == LessonAccess.PRO

    /** True when [lesson] must open the paywall instead of the lesson. */
    fun isLocked(lesson: LessonModel): Boolean =
        !AccessPolicy.isLessonUnlocked(lesson, status, inProgressLessonId, productAccess)

    /** True when the Pro [feature] must open the paywall instead of the surface. */
    fun isLocked(feature: ProFeature): Boolean =
        !AccessPolicy.isFeatureUnlocked(status, feature, productAccess)

    /** True when [lesson] may be downloaded for offline use by this learner. */
    fun canDownload(lesson: LessonModel): Boolean =
        AccessPolicy.canDownloadOffline(lesson, status, productAccess)
}
