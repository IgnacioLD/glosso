package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LessonStartLevelTest {

    @Test
    fun `opens on the level of the lesson in progress`() {
        assertEquals("B1", resolveStartupLevel("l27", "B1", "A2", "A1"))
    }

    @Test
    fun `the manual level wins over the last finished lesson`() {
        assertEquals("A1", resolveStartupLevel(null, null, "B2", "A1"))
        assertEquals("ALL", resolveStartupLevel(null, null, "B2", "ALL"))
    }

    @Test
    fun `opens on the level of the last finished lesson without a manual choice`() {
        assertEquals("B2", resolveStartupLevel(null, null, "B2", null))
    }

    @Test
    fun `falls back to A1 when nothing is known`() {
        assertEquals("A1", resolveStartupLevel(null, null, null, null))
    }

    @Test
    fun `a resume id without a saved level does not shadow the manual level`() {
        assertEquals("A1", resolveStartupLevel("l27", null, "B2", "A1"))
        assertEquals("B2", resolveStartupLevel("l27", null, "B2", null))
    }

    @Test
    fun `a manual level change is respected during the session`() {
        assertEquals("B2", levelForReload("B2", listOf("A1", "A2", "B1", "B2", "ALL")))
        assertEquals("A1", levelForReload("C9", listOf("A1", "A2", "ALL")))
    }

    @Test
    fun `several levels are offered with all levels last, a single one alone`() {
        assertEquals(listOf("A1", "A2", "B1", "ALL"), orderedLevels(listOf("B1", "A1", "A2", "A1", " ")))
        assertEquals(listOf("A1"), orderedLevels(listOf("A1")))
        assertEquals(emptyList<String>(), orderedLevels(emptyList()))
    }

    @Test
    fun `a course cut down to one level moves a learner off a level it no longer has`() {
        assertEquals("A1", levelForReload("ALL", orderedLevels(listOf("A1"))))
        assertEquals("A1", levelForReload("B2", orderedLevels(listOf("A1"))))
    }
}
