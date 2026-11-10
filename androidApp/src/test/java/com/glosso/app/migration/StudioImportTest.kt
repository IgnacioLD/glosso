package com.glosso.app.migration

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.glosso.app.LanguagePreferences
import com.glosso.app.OnboardingStore
import com.glosso.app.ProfileCodec
import com.glosso.app.ProgressData
import com.glosso.app.platform.currentAppLocale
import com.glosso.engine.model.PhonemeStatModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The upgrade path that matters: a real glosso-studio install is synthesized on
 * disk (prefs + the Room progress DB + per-level content DBs + the int8 model),
 * then [StudioImport] runs and the pro stores are read. A clean-install test
 * would pass by accident, so it is not enough. See docs/DATA-MIGRATION.md 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StudioImportTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** The identity hash Room writes for glosso-studio's DB v11 (schemas/.../11.json). */
    private val roomIdentityHash = "a8d4aa9227f4a601741b3a7c824c8e5c"

    private fun epochDay(offsetDays: Long): Long = LocalDate.now().plusDays(offsetDays).toEpochDay()

    private fun millisAt(offsetDays: Long): Long =
        LocalDate.now().plusDays(offsetDays).atTime(LocalTime.NOON)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `a clean install imports nothing and is not marked as migrated`() {
        StudioImport.runIfNeeded(app)

        val marker = prefs(StudioImport.MARKER_FILE)
        assertTrue(marker.getBoolean(StudioImport.FRESH_INSTALL, false))
        assertNull(marker.getString(StudioImport.MARKER_V1, null))
        assertTrue(prefs("glosso_language").all.isEmpty())
        assertTrue(prefs("glosso_onboarding").all.isEmpty())
        assertTrue(prefs("glosso_progress").all.isEmpty())
        assertTrue(prefs("glosso_stats").all.isEmpty())
        assertTrue(prefs("glosso_profile").all.isEmpty())
    }

    @Test
    fun `an upgrade from the published app imports what maps and keeps the sources`() {
        seedStudioInstall()
        assertProStoresEmpty()

        StudioImport.runIfNeeded(app)
        assertNotNull(prefs(StudioImport.MARKER_FILE).getString(StudioImport.MARKER_V1, null))

        // 1-3: target language, onboarding and the startup-level hint.
        LanguagePreferences.init(app)
        assertEquals("en", LanguagePreferences.code.value)
        OnboardingStore.init(app)
        assertTrue(OnboardingStore.done.value)
        assertEquals("B1", prefs("glosso_progress").getString("level", null))

        // 4: no lesson is invented, so nothing is resumed or finished.
        assertNull(prefs("glosso_progress").getString("lesson_id", null))
        assertNull(prefs("glosso_progress").getString("resume_level", null))

        // 14: the interface language maps on Android 13+, where the per-app API exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals("es", currentAppLocale(app))
        }

        // 5-7: activity survives as epoch days; "mastered" never becomes a lesson.
        val progress = readProgress()
        val expectedDays = listOf(-1L, -2L, -3L, -9L, -10L, -12L, -15L, -20L, -25L, -30L)
            .map(::epochDay)
            .toSet()
        assertTrue(progress.days.keys.map { it.toLong() }.containsAll(expectedDays))
        assertTrue(progress.days.values.all { it >= 1 })
        assertTrue(progress.dailyStreak >= 1)
        assertTrue(progress.finishedLessons.isEmpty())

        // 8-9: best-effort phonetic seed, only for in-inventory symbols.
        val profile = ProfileCodec.decode(prefs("glosso_profile").getString("phonetic_profile", null))
        assertNotNull(profile)
        assertEquals(
            PhonemeStatModel("θ", attempts = 40, correct = 23),
            profile!!.stats.first { it.phoneme == "θ" },
        )
        assertEquals(
            PhonemeStatModel("s", attempts = 20, correct = 17),
            profile.stats.first { it.phoneme == "s" },
        )
        assertTrue(profile.stats.none { it.phoneme == "ɣ" })

        // 12: the audit report is honest about what was read and discarded.
        val report = readReport()
        assertEquals(30, report.sourceCounts["mastered_sentences"])
        assertEquals(10, report.sourceCounts["activity_days"])
        assertEquals(8, report.sourceCounts["review_queue"])
        assertEquals("en", report.mappedLanguage)
        assertTrue(report.errors.isEmpty())
        assertEquals(38, report.groups.getValue("progress_db").discarded)
        assertTrue(report.groups.getValue("progress_db").reasons.containsKey("no_srs"))

        // 15-18, 20: discarded state never leaks into any pro store.
        val discardedKeys = setOf(
            "theme_mode", "ipa_visible", "translation_visible", "feedback_sounds", "playback_speed",
            "selected_voice", "tutorial_shown", "latin_warning_acknowledged", "mastery_combo",
            "best_mastery_streak", "acknowledged_milestone", "last_review_prompt_ms",
            "migrated_to_v10", "migrated_to_v11b", "batch_level", "batch_queue",
            "batch_mastered_count", "batch_total", "batch_language",
        )
        val proKeys = listOf("glosso_language", "glosso_onboarding", "glosso_progress", "glosso_stats", "glosso_profile")
            .flatMap { prefs(it).all.keys }
        assertTrue("discarded keys leaked: ${proKeys.filter { it in discardedKeys }}", proKeys.none { it in discardedKeys })
        val progressJson = prefs("glosso_stats").getString("progress", "").orEmpty()
        assertFalse(progressJson.contains("studio sentence"))
        assertFalse(progressJson.contains("review sentence"))

        // 19: studio's cached content is not adopted by the offline store.
        assertFalse(File(app.filesDir, "offline/index.json").exists())

        // 10-11: the second launch is a no-op and the report does not change.
        val reportBefore = StudioImport.reportFile(app).readText()
        val profileBefore = profile
        val progressBefore = progress
        StudioImport.runIfNeeded(app)
        assertEquals(profileBefore, ProfileCodec.decode(prefs("glosso_profile").getString("phonetic_profile", null)))
        assertEquals(progressBefore, readProgress())
        assertEquals(reportBefore, StudioImport.reportFile(app).readText())

        // 13: the studio sources are left intact as evidence.
        assertTrue(prefs(StudioImport.STUDIO_PREFS_FILE).all.isNotEmpty())
        assertTrue(app.getDatabasePath(StudioImport.STUDIO_PROGRESS_DB).exists())
        assertTrue(File(app.getDatabasePath("sentences_v11_en_GB_0.db").path).exists())
        assertTrue(File(app.filesDir, "wav2vec2_espeak_cv_ft_int8.onnx").exists())
        assertTrue(File(app.filesDir, "espeak_vocab.json").exists())
    }

    private fun assertProStoresEmpty() {
        assertTrue(prefs("glosso_language").all.isEmpty())
        assertTrue(prefs("glosso_onboarding").all.isEmpty())
        assertTrue(prefs("glosso_progress").all.isEmpty())
        assertTrue(prefs("glosso_stats").all.isEmpty())
        assertTrue(prefs("glosso_profile").all.isEmpty())
        assertNull(prefs(StudioImport.MARKER_FILE).getString(StudioImport.MARKER_V1, null))
    }

    @Test
    fun `a source that cannot be read fails its group and is retried, not marked done`() {
        seedStudioPrefs()
        val dbFile = app.getDatabasePath(StudioImport.STUDIO_PROGRESS_DB)
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("this is not a sqlite database")

        StudioImport.runIfNeeded(app)

        val marker = prefs(StudioImport.MARKER_FILE)
        assertNull(marker.getString(StudioImport.MARKER_V1, null))
        assertNull(marker.getString("${StudioImport.MARKER_V1}.progress_db", null))
        assertEquals(1, marker.getInt("${StudioImport.MARKER_V1}.progress_db.attempts", 0))
        assertEquals("ok", marker.getString("${StudioImport.MARKER_V1}.prefs", null))
        assertEquals("ok", marker.getString("${StudioImport.MARKER_V1}.phonetic_profile", null))

        val first = readReport()
        assertEquals("imported", first.groups.getValue("prefs").status)
        assertEquals("imported", first.groups.getValue("phonetic_profile").status)
        assertEquals("retry", first.groups.getValue("progress_db").status)
        assertTrue(first.errors.any { it.startsWith("progress_db") })

        // A retry keeps every group in the report, including the ones already done,
        // and grows the attempts counter.
        StudioImport.runIfNeeded(app)
        val second = readReport()
        assertEquals(2, marker.getInt("${StudioImport.MARKER_V1}.progress_db.attempts", 0))
        assertEquals(setOf("prefs", "progress_db", "phonetic_profile"), second.groups.keys)
        assertEquals("already_done", second.groups.getValue("prefs").status)
        assertEquals("already_done", second.groups.getValue("phonetic_profile").status)
        assertEquals("retry", second.groups.getValue("progress_db").status)
        assertNull(marker.getString(StudioImport.MARKER_V1, null))

        // The third attempt hits the cap: the group is abandoned, the global marker
        // is written so a permanently corrupt source stops retrying, and the source
        // is never deleted.
        StudioImport.runIfNeeded(app)
        val third = readReport()
        assertEquals("abandoned", third.groups.getValue("progress_db").status)
        assertTrue(third.abandoned.contains("progress_db"))
        assertNotNull(marker.getString(StudioImport.MARKER_V1, null))
        assertTrue(dbFile.exists())
    }

    private fun readProgress(): ProgressData {
        val raw = prefs("glosso_stats").getString("progress", null)
        assertNotNull("the progress store should have been seeded", raw)
        return json.decodeFromString(ProgressData.serializer(), raw!!)
    }

    private fun readReport(): MigrationReport =
        json.decodeFromString(MigrationReport.serializer(), StudioImport.reportFile(app).readText())

    /** A ~2-month glosso-studio install, built on the exported v11 schema. */
    private fun seedStudioInstall() {
        seedStudioPrefs()
        seedProgressDb()
        seedContentDb()
        File(app.filesDir, "wav2vec2_espeak_cv_ft_int8.onnx").writeBytes(ByteArray(16) { it.toByte() })
        File(app.filesDir, "espeak_vocab.json").writeText("""{"dummy":true}""")
    }

    private fun seedStudioPrefs() {
        prefs(StudioImport.STUDIO_PREFS_FILE).edit()
            .putString("target_language", "en_GB")
            .putString("ui_language", "es")
            .putInt("theme_mode", 2)
            .putBoolean("ipa_visible", true)
            .putBoolean("translation_visible", false)
            .putBoolean("feedback_sounds", true)
            .putBoolean("onboarding_shown", true)
            .putBoolean("tutorial_shown", true)
            .putBoolean("latin_warning_acknowledged", false)
            .putInt("selected_voice", 1)
            .putFloat("playback_speed", 0.9f)
            .putInt("last_level", 2)
            .putInt("mastery_combo", 4)
            .putInt("best_mastery_streak", 12)
            .putInt("acknowledged_milestone", 2)
            .putLong("last_review_prompt_ms", 1_725_000_000_000L)
            .putBoolean("migrated_to_v10", true)
            .putBoolean("migrated_to_v11b", true)
            .putInt("ph_total_θ", 40)
            .putInt("ph_miss_θ", 17)
            .putInt("ph_total_s", 20)
            .putInt("ph_miss_s", 3)
            .putInt("ph_total_ɣ", 5)
            .putInt("ph_miss_ɣ", 5)
            .commit()
    }

    private fun seedProgressDb() {
        val file = app.getDatabasePath(StudioImport.STUDIO_PROGRESS_DB)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS mastered_sentences (text TEXT NOT NULL, " +
                    "levelIndex INTEGER NOT NULL, masteredAt INTEGER NOT NULL, topic TEXT NOT NULL, " +
                    "language TEXT NOT NULL DEFAULT 'en', PRIMARY KEY(text, language))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS activity_days (dateString TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, language TEXT NOT NULL DEFAULT 'en', " +
                    "PRIMARY KEY(dateString, language))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS review_queue (text TEXT NOT NULL, " +
                    "levelIndex INTEGER NOT NULL, nextReviewAt INTEGER NOT NULL, " +
                    "intervalDays INTEGER NOT NULL, language TEXT NOT NULL DEFAULT 'en', " +
                    "PRIMARY KEY(text, language))",
            )

            val masteredDates = listOf(-1L, -2L, -3L, -5L, -10L, -15L, -20L, -25L)
            for (i in 0 until 30) {
                val day = masteredDates[i % masteredDates.size]
                val language = when (i) {
                    28 -> "fr"
                    29 -> "en"
                    else -> "en_GB"
                }
                db.insert(
                    "mastered_sentences",
                    null,
                    ContentValues().apply {
                        put("text", "studio sentence $i")
                        put("levelIndex", i % 3)
                        put("masteredAt", millisAt(day))
                        put("topic", "topic-${i % 4}")
                        put("language", language)
                    },
                )
            }

            for (offset in listOf(-1L, -2L, -3L, -9L, -10L, -12L, -15L, -20L, -25L, -30L)) {
                db.insert(
                    "activity_days",
                    null,
                    ContentValues().apply {
                        put("dateString", LocalDate.now().plusDays(offset).toString())
                        put("timestamp", millisAt(offset))
                        put("language", "en_GB")
                    },
                )
            }

            for (i in 0 until 8) {
                db.insert(
                    "review_queue",
                    null,
                    ContentValues().apply {
                        put("text", "review sentence $i")
                        put("levelIndex", i % 3)
                        put("nextReviewAt", millisAt(i - 4L))
                        put("intervalDays", intArrayOf(1, 3, 7)[i % 3])
                        put("language", if (i == 7) "fr" else "en_GB")
                    },
                )
            }
            markAsRoomDatabase(db)
        }
    }

    private fun markAsRoomDatabase(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
            arrayOf(roomIdentityHash),
        )
        db.version = 11
    }

    private fun seedContentDb() {
        val file = app.getDatabasePath("sentences_v11_en_GB_0.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sentences (id INTEGER NOT NULL PRIMARY KEY, " +
                    "text TEXT NOT NULL, ipa TEXT, level TEXT, topic TEXT, language TEXT, " +
                    "audio_b64 TEXT, audio_b64_2 TEXT, word_offsets TEXT DEFAULT '', " +
                    "translation_en TEXT, translation_es TEXT, translation_fr TEXT, translation_de TEXT)",
            )
            for (i in 0 until 3) {
                db.insert(
                    "sentences",
                    null,
                    ContentValues().apply {
                        put("id", i)
                        put("text", "content sentence $i")
                        put("ipa", "kənˈtent")
                        put("level", "A1")
                        put("topic", "greetings")
                        put("language", "en_GB")
                    },
                )
            }
            markAsRoomDatabase(db)
        }
        assertFalse(File(app.getDatabasePath("sentences_v11_en_GB_0.db").path).length() == 0L)
    }
}
