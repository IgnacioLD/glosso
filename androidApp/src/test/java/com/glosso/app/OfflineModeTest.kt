package com.glosso.app

import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DialogueTurn
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.WordModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The offline pack bookkeeping the whole "what is downloaded" answer rests on:
 * which packs are usable for the served catalogue, and which assets a pack
 * carries. Both are pure, so they are the cheap guard against a pack from an old
 * version being served or an asset being missed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineModeTest {

    private fun meta(id: String, version: String, at: Long) = OfflineMeta(
        id = id,
        title = id,
        theme = "city",
        level = "A1",
        downloadedAt = at,
        contentVersion = version,
    )

    @Test
    fun `packs for an older catalogue are not usable and are offered again`() {
        val index = OfflineIndex(
            lessons = mapOf(
                "old" to meta("old", "v1", 1),
                "current" to meta("current", "v2", 2),
                "unstamped" to meta("unstamped", "", 3),
            ),
        )

        val usable = index.usableLessons("v2").map { it.id }

        assertEquals(listOf("current"), usable)
    }

    @Test
    fun `nothing is usable until the served version is known`() {
        val index = OfflineIndex(lessons = mapOf("a" to meta("a", "v2", 1)))
        assertTrue(index.usableLessons("").isEmpty())
    }

    @Test
    fun `usable packs come back oldest first`() {
        val index = OfflineIndex(
            lessons = mapOf(
                "b" to meta("b", "v1", 20),
                "a" to meta("a", "v1", 10),
                "c" to meta("c", "v1", 30),
            ),
        )

        assertEquals(listOf("a", "b", "c"), index.usableLessons("v1").map { it.id })
    }

    @Test
    fun `a bundle lists its audio and artwork once, and drops blanks`() {
        val bundle = OfflineBundle(
            lesson = LessonModel(id = "l1", title = "Lesson", theme = "city", level = "A1"),
            dialogue = DialogueModel(
                id = "d1",
                title = "A call",
                image = "images/call.png",
                turns = listOf(
                    DialogueTurn(
                        speaker = "A",
                        text = "Hello",
                        audio = mapOf("en-GB" to "audio/hello.ogg", "en-US" to ""),
                    ),
                    DialogueTurn(
                        speaker = "B",
                        text = "Hi",
                        audio = mapOf("en-GB" to "audio/hello.ogg"),
                    ),
                ),
            ),
            words = listOf(
                WordModel(
                    id = "w1",
                    lemma = "call",
                    audio = mapOf("en-GB" to "audio/call.ogg", "en-US" to "audio/call.ogg"),
                ),
            ),
        )

        val paths = bundle.assetPaths()

        assertEquals(listOf("images/call.png", "audio/hello.ogg", "audio/call.ogg"), paths)
    }
}
