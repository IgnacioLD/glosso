package com.glosso.app.billing

import com.glosso.engine.model.LessonModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {

    private fun lesson(
        id: String,
        level: String = "A1",
        theme: String = "city",
    ) = LessonModel(id = id, level = level, theme = theme, title = id)

    // The catalog arrives in the order the server happens to return: the teaching
    // order is not its order, so only the published order can pick the first one.
    private val catalog = listOf(
        lesson("city.1", theme = "city"),
        lesson("family.1", theme = "family"),
        lesson("food.1", theme = "food"),
        lesson("a2.city.1", level = "A2", theme = "city"),
        lesson("b1.city.1", level = "B1", theme = "city"),
        lesson("b2.city.1", level = "B2", theme = "city"),
    )

    // What the manifest publishes: the pedagogical order, not alphabetical.
    private val themeOrder = listOf("family", "travel", "city", "food")

    private val free = catalog.first { it.id == "family.1" }
    private val a2 = catalog.first { it.id == "a2.city.1" }

    @Test
    fun `the whole of A1 is free and every later level is Pro`() {
        for (item in catalog) {
            if (item.level == "A1") {
                assertTrue("A1 should be free: ${item.id}", AccessPolicy.isLessonFree(item))
            } else {
                assertFalse("only A1 is free: ${item.id}", AccessPolicy.isLessonFree(item))
            }
        }
        // A1/EXPIRED and PENDING both mean "no entitlement": the A1 level only.
        assertFalse(AccessPolicy.requiresPaywall(free, SubscriptionStatus.NONE))
        for (level in listOf("A2", "B1", "B2")) {
            val paid = catalog.first { it.level == level }
            assertTrue(
                "the wall is the CEFR level, not a block: $level",
                AccessPolicy.requiresPaywall(paid, SubscriptionStatus.NONE),
            )
        }
    }

    @Test
    fun `the block order follows the manifest and appends unknown themes`() {
        val withExtra = catalog + lesson("airport.1", theme = "airport")
        assertEquals(
            listOf("family", "city", "food", "airport"),
            AccessPolicy.blockOrder(withExtra, themeOrder),
        )
    }

    @Test
    fun `the first lesson is the first theme in teaching order`() {
        assertEquals("family.1", AccessPolicy.firstLesson(catalog, themeOrder)?.id)
        assertNull(AccessPolicy.firstLesson(emptyList(), themeOrder))
    }

    @Test
    fun `inside a block a dialogue comes before a story and an article, like the list`() {
        val block = listOf(
            LessonModel(id = "family.a", theme = "family", title = "article", articleId = "x"),
            LessonModel(id = "family.b", theme = "family", title = "story", storyId = "x"),
            LessonModel(id = "family.c", theme = "family", title = "dialogue", dialogueId = "x"),
        )

        assertEquals(
            listOf("family.c", "family.b", "family.a"),
            AccessPolicy.teachingOrder(block, themeOrder).map { it.id },
        )
        assertEquals("family.c", AccessPolicy.firstLesson(block, themeOrder)?.id)
        assertEquals("family.b", AccessPolicy.nextLesson(block, themeOrder, listOf("family.c"))?.id)
    }

    @Test
    fun `a paid lesson shows the paywall before starting, never mid-lesson`() {
        assertFalse(AccessPolicy.requiresPaywall(free, SubscriptionStatus.NONE))
        assertTrue(AccessPolicy.requiresPaywall(a2, SubscriptionStatus.NONE))
    }

    @Test
    fun `a lesson already in progress can always be finished`() {
        assertTrue(
            AccessPolicy.isLessonUnlocked(a2, SubscriptionStatus.NONE, inProgressLessonId = a2.id),
        )
        assertFalse(
            AccessPolicy.requiresPaywall(a2, SubscriptionStatus.NONE, inProgressLessonId = a2.id),
        )
    }

    @Test
    fun `a lesson started free is finished free under every status`() {
        // "Empezada gratis": a free lesson is never gated, whatever the entitlement
        // does later, so the learner who began it finishes it without paying.
        for (status in SubscriptionStatus.entries) {
            assertFalse(
                "free lesson blocked for $status",
                AccessPolicy.requiresPaywall(free, status, inProgressLessonId = free.id),
            )
        }
    }

    @Test
    fun `entitlement unlocks the whole catalog`() {
        val entitled = listOf(
            SubscriptionStatus.TRIAL,
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.GRACE,
            SubscriptionStatus.CANCELED,
        )
        for (status in entitled) {
            assertTrue(AccessPolicy.isEntitled(status))
            for (item in catalog) {
                assertFalse(AccessPolicy.requiresPaywall(item, status))
            }
        }
    }

    @Test
    fun `pending is not entitled`() {
        assertFalse(AccessPolicy.isEntitled(SubscriptionStatus.PENDING))
        assertTrue(AccessPolicy.requiresPaywall(a2, SubscriptionStatus.PENDING))
    }

    @Test
    fun `the level frontier reads the same for every content kind`() {
        assertTrue(AccessPolicy.isLevelFree("A1"))
        assertFalse(AccessPolicy.isLevelFree("A2"))
        assertFalse(AccessPolicy.isLevelFree("B1"))
        assertFalse(AccessPolicy.isLevelFree("B2"))
        assertEquals(LessonAccess.FREE, AccessPolicy.accessOf("A1"))
        assertEquals(LessonAccess.PRO, AccessPolicy.accessOf("B2"))
    }

    @Test
    fun `the Pro features follow the entitlement`() {
        assertFalse(
            AccessPolicy.isFeatureUnlocked(SubscriptionStatus.NONE, ProFeature.PERSONALIZED_PRACTICE),
        )
        assertFalse(
            AccessPolicy.isFeatureUnlocked(SubscriptionStatus.PENDING, ProFeature.PHONETIC_PROFILE),
        )
        assertTrue(
            AccessPolicy.isFeatureUnlocked(SubscriptionStatus.CANCELED, ProFeature.PERSONALIZED_PRACTICE),
        )
        assertTrue(
            AccessPolicy.isFeatureUnlocked(SubscriptionStatus.GRACE, ProFeature.PHONETIC_PROFILE),
        )
    }

    @Test
    fun `offline downloads mirror the level frontier`() {
        assertTrue(AccessPolicy.canDownloadOffline(free, SubscriptionStatus.NONE))
        assertFalse(AccessPolicy.canDownloadOffline(a2, SubscriptionStatus.NONE))
        assertTrue(AccessPolicy.canDownloadOffline(a2, SubscriptionStatus.ACTIVE))
    }

    // ------------------------------------------------------------ access matrix

    /**
     * The full matrix: every subscription status crossed with free (A1) content,
     * Pro (A2) content and offline download. Every member is asserted so a new one
     * cannot slip through untested.
     */
    @Test
    fun `access matrix over every subscription status`() {
        data class Row(
            val status: SubscriptionStatus,
            val pro: Boolean,
            val opensFree: Boolean,
            val opensPro: Boolean,
            val downloadsFree: Boolean,
            val downloadsPro: Boolean,
        )

        val rows = listOf(
            // FREE and EXPIRED both mean "no entitlement": A1 only.
            Row(SubscriptionStatus.NONE, false, true, false, true, false),
            Row(SubscriptionStatus.TRIAL, true, true, true, true, true),
            Row(SubscriptionStatus.ACTIVE, true, true, true, true, true),
            Row(SubscriptionStatus.GRACE, true, true, true, true, true),
            Row(SubscriptionStatus.CANCELED, true, true, true, true, true),
            Row(SubscriptionStatus.PENDING, false, true, false, true, false),
        )

        for (row in rows) {
            assertEquals("isEntitled for ${row.status}", row.pro, AccessPolicy.isEntitled(row.status))
            assertEquals(
                "open A1 for ${row.status}",
                row.opensFree,
                !AccessPolicy.requiresPaywall(free, row.status),
            )
            assertEquals(
                "open A2 for ${row.status}",
                row.opensPro,
                !AccessPolicy.requiresPaywall(a2, row.status),
            )
            assertEquals(
                "download A1 for ${row.status}",
                row.downloadsFree,
                AccessPolicy.canDownloadOffline(free, row.status),
            )
            assertEquals(
                "download A2 for ${row.status}",
                row.downloadsPro,
                AccessPolicy.canDownloadOffline(a2, row.status),
            )
        }
    }
}
