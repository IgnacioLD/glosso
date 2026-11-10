package com.glosso.app.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import com.glosso.app.BuildConfig
import com.glosso.app.OfferedLanguages
import com.glosso.app.ProfileCodec
import com.glosso.app.ProfileStore
import com.glosso.app.ProgressData
import com.glosso.app.platform.setAppLocale
import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One-time import of the state left by the published glosso-studio app into the
 * new glosso-pro stores. The published `applicationId` is preserved on purpose,
 * so the two apps share `/data/data/me.shirobyte42.glosso/` and Android installs
 * this build over the old one; no backup/restore or cross-package provider is
 * needed.
 *
 * Contract, from docs/DATA-MIGRATION.md:
 * - Reads studio read-only and writes only new pro keys. It never deletes.
 * - Idempotent: a versioned marker (`glosso_migration.studio_import_v1`) plus a
 *   per-group marker short-circuit a second run; every source is a full value
 *   replacement, so a retry after a crash converges to the same result.
 * - Failing groups retry on later launches and are abandoned after a few tries,
 *   never blocking startup and never wiping anything.
 * - An audit report is left at `filesDir/migration/studio-import-v1.json`.
 *
 * It must run before any pro store reads, which is why [com.glosso.app.GlossoApplication]
 * calls it first in `onCreate`.
 */
object StudioImport {

    private const val TAG = "GlossoMigration"

    internal const val STUDIO_PREFS_FILE = "glosso_prefs"
    internal const val STUDIO_PROGRESS_DB = "glosso_progress_db"
    internal const val MARKER_FILE = "glosso_migration"
    internal const val MARKER_V1 = "studio_import_v1"
    internal const val FRESH_INSTALL = "fresh_install"
    internal const val REPORT_NAME = "studio-import-v1.json"

    private const val MAX_ATTEMPTS = 3
    private const val REPORT_DIR = "migration"

    private const val G_PREFS = "prefs"
    private const val G_DB = "progress_db"
    private const val G_PROFILE = "phonetic_profile"
    private val GROUPS = listOf(G_PREFS, G_DB, G_PROFILE)

    /** The CEFR ladder studio indexes into with `last_level` (0..5). */
    private val LEVEL_CODES = listOf("A1", "A2", "B1", "B2", "C1", "C2")

    /** The levels the pro curriculum publishes today (A1-B2). See docs/MERGE-FREE-PRO.md. */
    private val PUBLISHED_LEVELS = setOf("A1", "A2", "B1", "B2")

    private val KNOWN_KEYS = setOf(
        "target_language", "ui_language", "theme_mode", "ipa_visible", "translation_visible",
        "feedback_sounds", "onboarding_shown", "tutorial_shown", "latin_warning_acknowledged",
        "selected_voice", "playback_speed", "last_level", "mastery_combo", "best_mastery_streak",
        "acknowledged_milestone", "last_review_prompt_ms", "migrated_to_v10", "migrated_to_v11b",
        "batch_level", "batch_queue", "batch_mastered_count", "batch_total", "batch_language",
    )

    private val NO_EQUIVALENT_KEYS = setOf(
        "theme_mode", "ipa_visible", "translation_visible", "feedback_sounds", "tutorial_shown",
        "latin_warning_acknowledged", "playback_speed", "mastery_combo", "best_mastery_streak",
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Never throws: a failure here must not stop the app from starting. */
    fun runIfNeeded(context: Context) {
        runCatching { run(context) }
            .onFailure { Log.w(TAG, "studio import skipped: ${it.message}") }
    }

    internal fun reportFile(context: Context): File =
        File(File(context.filesDir, REPORT_DIR), REPORT_NAME)

    private fun run(context: Context) {
        val marker = context.getSharedPreferences(MARKER_FILE, Context.MODE_PRIVATE)
        val studioPrefs = context.getSharedPreferences(STUDIO_PREFS_FILE, Context.MODE_PRIVATE).all
        val hasPrefs = studioPrefs.keys.any(::isStudioKey)
        val dbFile = context.getDatabasePath(STUDIO_PROGRESS_DB)
        val hasDb = dbFile.exists()

        if (!hasPrefs && !hasDb) {
            if (!marker.getBoolean(FRESH_INSTALL, false)) {
                marker.edit().putBoolean(FRESH_INSTALL, true).apply()
            }
            Log.i(TAG, "no glosso-studio data; fresh install, nothing to import")
            return
        }
        if (marker.getString(MARKER_V1, null) != null) {
            Log.i(TAG, "studio import already completed")
            return
        }

        val mappedLanguage = OfferedLanguages.orNull(studioPrefs["target_language"] as? String)?.code
        val db = readStudioDb(dbFile)

        val errors = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val keysWritten = mutableListOf<String>()
        // Every group is always reported, including the ones already done on a
        // previous launch: a retry must not drop earlier groups from the audit.
        val groups = GROUPS.associateWith { GroupReport(status = groupStatus(marker, it)) }.toMutableMap()
        val attempts = GROUPS.associateWith { marker.getInt("$MARKER_V1.$it.attempts", 0) }.toMutableMap()
        val abandoned = GROUPS.filter { marker.getBoolean("$MARKER_V1.$it.abandoned", false) }.toMutableList()
        val sourceCounts = mutableMapOf(
            "mastered_sentences" to db.mastered.size,
            "activity_days" to db.activity.size,
            "review_queue" to db.review.size,
        )
        errors += db.errors

        fun pending(group: String): Boolean = groups.getValue(group).status == "pending"

        fun succeed(group: String) {
            marker.edit().putString("$MARKER_V1.$group", "ok").putInt("$MARKER_V1.$group.attempts", 0).apply()
            groups.getValue(group).status = "imported"
            Log.i(TAG, "$group imported")
        }

        fun fail(group: String, error: String) {
            val tries = marker.getInt("$MARKER_V1.$group.attempts", 0) + 1
            attempts[group] = tries
            marker.edit().putInt("$MARKER_V1.$group.attempts", tries).apply()
            errors += "$group: $error"
            if (tries >= MAX_ATTEMPTS) {
                marker.edit().putBoolean("$MARKER_V1.$group.abandoned", true).apply()
                if (group !in abandoned) abandoned += group
                groups.getValue(group).status = "abandoned"
            } else {
                groups.getValue(group).status = "retry"
            }
            Log.w(TAG, "$group import failed (attempt $tries): $error")
        }

        if (pending(G_PREFS)) {
            runCatching { importPrefs(context, studioPrefs, mappedLanguage, groups.getValue(G_PREFS), keysWritten, conflicts) }
                .onSuccess { succeed(G_PREFS) }
                .onFailure { fail(G_PREFS, it.message ?: it::class.simpleName ?: "error") }
        }

        if (pending(G_DB)) {
            runCatching { importProgress(context, db, mappedLanguage, groups.getValue(G_DB), keysWritten, conflicts) }
                .onSuccess { succeed(G_DB) }
                .onFailure { fail(G_DB, it.message ?: it::class.simpleName ?: "error") }
        }

        if (pending(G_PROFILE)) {
            runCatching { importProfile(context, studioPrefs, mappedLanguage, groups.getValue(G_PROFILE), keysWritten, conflicts) }
                .onSuccess { succeed(G_PROFILE) }
                .onFailure { fail(G_PROFILE, it.message ?: it::class.simpleName ?: "error") }
        }

        val finished = GROUPS.all { group ->
            marker.getString("$MARKER_V1.$group", null) != null ||
                marker.getBoolean("$MARKER_V1.$group.abandoned", false)
        }
        if (finished) {
            marker.edit().putString(MARKER_V1, "${System.currentTimeMillis()}:${BuildConfig.VERSION_CODE}").apply()
        }

        writeReport(
            context,
            MigrationReport(
                ranAtMs = System.currentTimeMillis(),
                appVersionCode = BuildConfig.VERSION_CODE,
                detection = DetectionReport(
                    studioPrefs = hasPrefs,
                    studioProgressDb = hasDb,
                    studioContentDbs = listStudioContentDbs(context),
                    modelFiles = listStudioModelFiles(context),
                ),
                mappedLanguage = mappedLanguage,
                sourceCounts = sourceCounts,
                groups = groups,
                keysWritten = keysWritten,
                conflicts = conflicts,
                errors = errors,
                attempts = attempts,
                abandoned = abandoned,
            ),
        )
    }

    private fun importPrefs(
        context: Context,
        studioPrefs: Map<String, *>,
        mappedLanguage: String?,
        report: GroupReport,
        keysWritten: MutableList<String>,
        conflicts: MutableList<String>,
    ) {
        report.read += studioPrefs.keys.count { !it.startsWith("ph_total_") && !it.startsWith("ph_miss_") }

        val rawTarget = studioPrefs["target_language"] as? String
        if (!rawTarget.isNullOrBlank()) {
            if (mappedLanguage != null) {
                if (writeStringIfAbsent(context, "glosso_language", "target_language", mappedLanguage, keysWritten, conflicts)) {
                    report.mapped++
                }
            } else {
                report.discard("unsupported_language")
            }
        }

        if (studioPrefs["onboarding_shown"] == true) {
            if (writeBooleanIfAbsent(context, "glosso_onboarding", "done", true, keysWritten, conflicts)) {
                report.mapped++
            }
        }

        (studioPrefs["last_level"] as? Int)?.let { index ->
            val cefr = LEVEL_CODES.getOrNull(index)
            if (cefr != null && cefr in PUBLISHED_LEVELS) {
                if (writeStringIfAbsent(context, "glosso_progress", "level", cefr, keysWritten, conflicts)) {
                    report.mapped++
                }
            } else {
                report.discard("level_not_published")
            }
        }

        (studioPrefs["ui_language"] as? String)?.takeIf { it.isNotBlank() }?.let { tag ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { setAppLocale(context, tag) }
                    .onSuccess { report.mapped++ }
                    .onFailure { report.discard("ui_locale_failed") }
            } else {
                report.discard("ui_locale_unsupported_below_33")
            }
        }

        for (key in studioPrefs.keys) {
            if (key.startsWith("ph_total_") || key.startsWith("ph_miss_")) continue
            if (key in NO_EQUIVALENT_KEYS) report.discard("no_equivalent")
        }
        report.discarded = report.reasons.values.sum().coerceAtLeast(report.read - report.mapped)
    }

    private fun importProgress(
        context: Context,
        db: StudioDb,
        mappedLanguage: String?,
        report: GroupReport,
        keysWritten: MutableList<String>,
        conflicts: MutableList<String>,
    ) {
        report.read = db.activity.size

        // A source that exists but could not be read (corrupt, locked, WAL) must
        // fail the group so it is retried, not silently marked as done. See
        // docs/DATA-MIGRATION.md 4.7.
        if (db.errors.isNotEmpty()) {
            error("source read failed: ${db.errors.joinToString("; ")}")
        }

        if (mappedLanguage == null) {
            report.discardAll(db.activity.size + db.mastered.size + db.review.size, "unsupported_language")
            return
        }

        val days = sortedSetOf<Long>()
        for (row in db.activity) {
            if (!sameLanguage(row.language, mappedLanguage)) continue
            toEpochDay(row.dateString)?.let { days += it }
        }
        var fromMastered = 0
        for (row in db.mastered) {
            if (!sameLanguage(row.language, mappedLanguage)) continue
            toEpochDayMillis(row.masteredAt)?.let { if (days.add(it)) fromMastered++ }
        }

        if (days.isNotEmpty()) {
            val today = LocalDate.now().toEpochDay()
            val last = days.max()
            val data = ProgressData(
                days = days.associate { it.toString() to 1 },
                dailyStreak = streakEndingAt(days, last, today),
                lastTakeDay = last,
            )
            writeStringIfAbsent(
                context,
                "glosso_stats",
                "progress",
                json.encodeToString(ProgressData.serializer(), data),
                keysWritten,
                conflicts,
            )
        }

        report.mapped = days.size
        report.reasons["activity_days_imported"] = days.size
        report.reasons["activity_days_from_mastered"] = fromMastered
        report.reasons["no_srs"] = db.review.size
        report.reasons["no_equivalent"] = db.mastered.size
        report.discarded = db.review.size + db.mastered.size
    }

    private fun importProfile(
        context: Context,
        studioPrefs: Map<String, *>,
        mappedLanguage: String?,
        report: GroupReport,
        keysWritten: MutableList<String>,
        conflicts: MutableList<String>,
    ) {
        val totals = mutableMapOf<String, Int>()
        val misses = mutableMapOf<String, Int>()
        for ((key, value) in studioPrefs) {
            val amount = value as? Int ?: continue
            when {
                key.startsWith("ph_total_") -> totals[key.removePrefix("ph_total_")] = amount
                key.startsWith("ph_miss_") -> misses[key.removePrefix("ph_miss_")] = amount
            }
        }
        report.read = totals.size

        if (mappedLanguage == null) {
            report.discardAll(totals.size, "unsupported_language")
            return
        }

        val inventory = LanguagePacks.byCode(mappedLanguage).phonemeInventory
        val stats = mutableListOf<PhonemeStatModel>()
        for ((phoneme, total) in totals) {
            when {
                total <= 0 -> report.discard("empty_total")
                phoneme !in inventory -> report.discard("not_in_inventory")
                else -> {
                    val missed = (misses[phoneme] ?: 0).coerceIn(0, total)
                    stats += PhonemeStatModel(phoneme, attempts = total, correct = total - missed)
                    report.mapped++
                }
            }
        }

        if (stats.isNotEmpty()) {
            writeStringIfAbsent(
                context,
                "glosso_profile",
                ProfileStore.keyFor(mappedLanguage),
                ProfileCodec.encode(PhoneticProfileModel(stats)),
                keysWritten,
                conflicts,
            )
        }
    }

    private fun readStudioDb(dbFile: File): StudioDb {
        if (!dbFile.exists()) return StudioDb()
        val errors = mutableListOf<String>()
        var mastered = emptyList<MasteredRow>()
        var activity = emptyList<ActivityRow>()
        var review = emptyList<ReviewRow>()
        runCatching {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                runCatching {
                    db.rawQuery(
                        "SELECT text, levelIndex, masteredAt, topic, language FROM mastered_sentences",
                        null,
                    ).use { cursor ->
                        val rows = mutableListOf<MasteredRow>()
                        while (cursor.moveToNext()) {
                            rows += MasteredRow(
                                text = cursor.getString(0),
                                levelIndex = cursor.getInt(1),
                                masteredAt = cursor.getLong(2),
                                topic = cursor.getString(3) ?: "",
                                language = cursor.getString(4) ?: "",
                            )
                        }
                        mastered = rows
                    }
                }.onFailure { errors += "mastered_sentences: ${it.message}" }
                runCatching {
                    db.rawQuery("SELECT dateString, timestamp, language FROM activity_days", null).use { cursor ->
                        val rows = mutableListOf<ActivityRow>()
                        while (cursor.moveToNext()) {
                            rows += ActivityRow(
                                dateString = cursor.getString(0) ?: "",
                                timestamp = cursor.getLong(1),
                                language = cursor.getString(2) ?: "",
                            )
                        }
                        activity = rows
                    }
                }.onFailure { errors += "activity_days: ${it.message}" }
                runCatching {
                    db.rawQuery(
                        "SELECT text, levelIndex, nextReviewAt, intervalDays, language FROM review_queue",
                        null,
                    ).use { cursor ->
                        val rows = mutableListOf<ReviewRow>()
                        while (cursor.moveToNext()) {
                            rows += ReviewRow(
                                text = cursor.getString(0) ?: "",
                                levelIndex = cursor.getInt(1),
                                nextReviewAt = cursor.getLong(2),
                                intervalDays = cursor.getInt(3),
                                language = cursor.getString(4) ?: "",
                            )
                        }
                        review = rows
                    }
                }.onFailure { errors += "review_queue: ${it.message}" }
            }
        }.onFailure { errors += "progress_db: ${it.message}" }
        return StudioDb(mastered, activity, review, errors)
    }

    private fun writeStringIfAbsent(
        context: Context,
        file: String,
        key: String,
        value: String,
        keysWritten: MutableList<String>,
        conflicts: MutableList<String>,
    ): Boolean {
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrEmpty()) {
            conflicts += "$file.$key: pro wins over studio"
            return false
        }
        prefs.edit().putString(key, value).apply()
        keysWritten += "$file.$key"
        return true
    }

    private fun writeBooleanIfAbsent(
        context: Context,
        file: String,
        key: String,
        value: Boolean,
        keysWritten: MutableList<String>,
        conflicts: MutableList<String>,
    ): Boolean {
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        if (prefs.contains(key)) {
            conflicts += "$file.$key: pro wins over studio"
            return false
        }
        prefs.edit().putBoolean(key, value).apply()
        keysWritten += "$file.$key"
        return true
    }

    private fun writeReport(context: Context, report: MigrationReport) {
        runCatching {
            val dir = File(context.filesDir, REPORT_DIR)
            dir.mkdirs()
            File(dir, REPORT_NAME).writeText(json.encodeToString(MigrationReport.serializer(), report))
        }.onFailure { Log.w(TAG, "could not write the migration report: ${it.message}") }
    }

    private fun isStudioKey(key: String): Boolean =
        key in KNOWN_KEYS || key.startsWith("ph_total_") || key.startsWith("ph_miss_")

    /** The persisted state of a group before this run: pending, done or abandoned. */
    private fun groupStatus(marker: android.content.SharedPreferences, group: String): String = when {
        marker.getString("$MARKER_V1.$group", null) != null -> "already_done"
        marker.getBoolean("$MARKER_V1.$group.abandoned", false) -> "abandoned"
        else -> "pending"
    }

    private fun sameLanguage(raw: String, code: String): Boolean =
        OfferedLanguages.orNull(raw)?.code == code

    private fun toEpochDay(dateString: String): Long? =
        runCatching { LocalDate.parse(dateString).toEpochDay() }.getOrNull()

    private fun toEpochDayMillis(millis: Long): Long? =
        runCatching {
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        }.getOrNull()

    /** Consecutive active days ending at [last], or 0 when the run is already broken. */
    private fun streakEndingAt(days: Set<Long>, last: Long, today: Long): Int {
        if (last < today - 1) return 0
        var streak = 0
        var day = last
        while (day in days) {
            streak++
            day--
        }
        return streak
    }

    private fun listStudioContentDbs(context: Context): List<String> =
        context.getDatabasePath(STUDIO_PROGRESS_DB).parentFile?.listFiles()
            ?.filter { it.name.startsWith("sentences") && it.name.endsWith(".db") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    private fun listStudioModelFiles(context: Context): List<String> =
        context.filesDir.listFiles()
            ?.filter { it.name.contains("wav2vec2") || it.name.contains("espeak_vocab") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    private data class StudioDb(
        val mastered: List<MasteredRow> = emptyList(),
        val activity: List<ActivityRow> = emptyList(),
        val review: List<ReviewRow> = emptyList(),
        val errors: List<String> = emptyList(),
    )

    private data class MasteredRow(
        val text: String,
        val levelIndex: Int,
        val masteredAt: Long,
        val topic: String,
        val language: String,
    )

    private data class ActivityRow(val dateString: String, val timestamp: Long, val language: String)

    private data class ReviewRow(
        val text: String,
        val levelIndex: Int,
        val nextReviewAt: Long,
        val intervalDays: Int,
        val language: String,
    )
}

/** Per-group tally for the audit report. */
@Serializable
internal data class GroupReport(
    /** pending | imported | retry | abandoned | already_done */
    var status: String = "pending",
    var read: Int = 0,
    var mapped: Int = 0,
    var discarded: Int = 0,
    var reasons: MutableMap<String, Int> = mutableMapOf(),
) {
    fun discard(reason: String) {
        discarded++
        reasons[reason] = (reasons[reason] ?: 0) + 1
    }

    fun discardAll(count: Int, reason: String) {
        if (count <= 0) return
        discarded = count
        reasons[reason] = count
    }
}

@Serializable
internal data class DetectionReport(
    val studioPrefs: Boolean,
    val studioProgressDb: Boolean,
    val studioContentDbs: List<String>,
    val modelFiles: List<String>,
)

/** The persistent, auditable trace of what the import saw and did. */
@Serializable
internal data class MigrationReport(
    val ranAtMs: Long,
    val appVersionCode: Int,
    val detection: DetectionReport,
    val mappedLanguage: String? = null,
    val sourceCounts: Map<String, Int> = emptyMap(),
    val groups: Map<String, GroupReport> = emptyMap(),
    val keysWritten: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val attempts: Map<String, Int> = emptyMap(),
    val abandoned: List<String> = emptyList(),
)
