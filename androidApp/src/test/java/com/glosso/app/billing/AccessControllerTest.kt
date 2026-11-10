package com.glosso.app.billing

import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessControllerTest {

    private fun lesson(
        id: String,
        level: String = "A1",
        theme: String = "family",
    ) = LessonModel(id = id, level = level, theme = theme, title = id)

    private fun story(id: String, level: String = "A1") =
        StoryModel(id = id, level = level, title = id)

    private fun exercise(id: String, level: String = "A1") =
        ExerciseModel(id = id, level = level, type = "multiple-choice", prompt = id)

    private fun drill(id: String, level: String = "A1") =
        DrillModel(id = id, level = level, target = "θ", text = id)

    private val a1 = lesson("family.1")
    private val a2 = lesson("city.2", level = "A2")

    private fun controller(
        status: SubscriptionStatus,
        inProgressLessonId: String? = null,
    ) = AccessController(status, inProgressLessonId)

    @Test
    fun `the controller exposes the entitlement and the free level once`() {
        val free = controller(SubscriptionStatus.NONE)
        assertFalse(free.entitled)
        assertEquals(LessonAccess.FREE, free.accessOf(a1))
        assertEquals(LessonAccess.PRO, free.accessOf(a2))
    }

    @Test
    fun `a free learner opens and downloads A1, but not A2`() {
        val free = controller(SubscriptionStatus.NONE)
        assertFalse(free.isLocked(a1))
        assertTrue(free.canDownload(a1))
        assertTrue(free.isLocked(a2))
        assertFalse(free.canDownload(a2))
    }

    @Test
    fun `an entitled learner opens and downloads everything`() {
        val pro = controller(SubscriptionStatus.ACTIVE)
        assertTrue(pro.entitled)
        assertFalse(pro.isLocked(a2))
        assertTrue(pro.canDownload(a2))
    }

    @Test
    fun `a paid lesson in progress is not locked after the entitlement lapses`() {
        val lapsed = controller(SubscriptionStatus.NONE, inProgressLessonId = a2.id)
        assertFalse(lapsed.isLocked(a2))
        // Being continuable does not turn it into a free download.
        assertFalse(lapsed.canDownload(a2))
    }

    @Test
    fun `pending does not unlock Pro content`() {
        val pending = controller(SubscriptionStatus.PENDING)
        assertFalse(pending.entitled)
        assertTrue(pending.isLocked(a2))
    }

    @Test
    fun `every content kind is gated by its own level`() {
        // The gate reads ContentMeta, not the screen: a story, an exercise and a
        // drill are judged exactly like a lesson, so no surface can invent a
        // frontier of its own.
        val free = controller(SubscriptionStatus.NONE)
        assertFalse(free.isLocked(story("s1")))
        assertTrue(free.isLocked(story("s2", level = "A2")))
        assertFalse(free.isLocked(exercise("e1")))
        assertTrue(free.isLocked(exercise("e2", level = "B1")))
        assertFalse(free.isLocked(drill("d1")))
        assertTrue(free.isLocked(drill("d2", level = "A2")))
        assertEquals(LessonAccess.FREE, free.accessOf(story("s1")))
        assertEquals(LessonAccess.PRO, free.accessOf(drill("d2", level = "A2")))

        val pro = controller(SubscriptionStatus.ACTIVE)
        assertFalse(pro.isLocked(story("s2", level = "A2")))
        assertFalse(pro.isLocked(exercise("e2", level = "B1")))
        assertFalse(pro.isLocked(drill("d2", level = "A2")))
    }

    @Test
    fun `the Pro features are locked for a free learner and open for a Pro one`() {
        val free = controller(SubscriptionStatus.NONE)
        assertTrue(free.isLocked(ProFeature.PERSONALIZED_PRACTICE))
        assertTrue(free.isLocked(ProFeature.PHONETIC_PROFILE))
        // A pending payment is not entitlement either.
        val pending = controller(SubscriptionStatus.PENDING)
        assertTrue(pending.isLocked(ProFeature.PERSONALIZED_PRACTICE))

        val pro = controller(SubscriptionStatus.TRIAL)
        assertFalse(pro.isLocked(ProFeature.PERSONALIZED_PRACTICE))
        assertFalse(pro.isLocked(ProFeature.PHONETIC_PROFILE))
    }
}
