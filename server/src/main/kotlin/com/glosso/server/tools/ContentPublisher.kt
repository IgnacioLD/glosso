package com.glosso.server.tools

import com.glosso.engine.drills.DrillBankLoader
import com.glosso.engine.drills.DrillValidator
import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.G2P
import com.glosso.engine.g2p.LexiconG2P
import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.model.AccentWording
import com.glosso.engine.model.ArticleBank
import com.glosso.engine.model.ContentManifest
import com.glosso.engine.model.DialogueBank
import com.glosso.engine.model.DrillBank
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.ExerciseBank
import com.glosso.engine.model.G2PKind
import com.glosso.engine.model.LanguagePack
import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.model.LessonBank
import com.glosso.engine.model.StoryBank
import com.glosso.engine.model.WordBank
import com.glosso.engine.model.forAccent
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val outJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

private var invalid = 0
private var drilledWithoutVariants = 0

/**
 * Compiles the authored content into the banks the app downloads: fills the
 * reference IPA where it applies and validates every item, failing the run on any
 * invalid one so a broken bank never ships. Handles every kind, not just drills.
 *
 * The target language is explicit: it selects the G2P (CMUdict for English, a
 * per-language lexicon built by `tools/content/phonemize.py` otherwise) and the
 * expected `language` field on every item.
 *
 * `--levels A1,A2` publishes only those CEFR levels: every item of another level
 * is left out of the banks, and so is a lesson whose base, exercises or words
 * were left out, so a course can ship a level at a time while the rest is still
 * being written.
 *
 *   ./gradlew :server:publishContent -Planguage=<code> [-Plevels=A1,A2]
 */
fun main(args: Array<String>) {
    fun opt(name: String): String? {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    val contentDir = File(opt("--content") ?: error("--content <dir> is required"))
    val language = opt("--language") ?: LanguagePacks.default.code
    val pack = LanguagePacks.byCodeOrNull(language)
        ?: error("unknown language '$language' (known: ${LanguagePacks.all.joinToString { it.code }})")
    val outDir = File(opt("--out") ?: "${contentDir.path}/dist")

    val g2p: G2P = loadG2p(pack, contentDir, ::opt)
        ?: error(
            "No G2P for '${pack.code}'. English needs --cmudict; other languages need " +
                "'${pack.g2pAsset.orEmpty()}' (run tools/content/phonemize.py --language ${pack.code}).",
        )

    val published = parseLevels(opt("--levels"))
    published?.let { println("LEVELS only ${it.joinToString()}") }
    val hiddenIds = mutableSetOf<String>()
    val hiddenCounts = LinkedHashMap<String, Int>()

    /** Keeps the items of a published level; remembers the rest, so lessons that use them are dropped too. */
    fun <T> List<T>.inPublishedLevels(kind: String, id: (T) -> String, level: (T) -> String): List<T> {
        if (published == null) return this
        val (kept, hidden) = partition { level(it) in published }
        hidden.forEach { hiddenIds += id(it) }
        if (hidden.isNotEmpty()) hiddenCounts[kind] = (hiddenCounts[kind] ?: 0) + hidden.size
        return kept
    }

    val accents = AccentContent.load(contentDir)
    val counts = LinkedHashMap<String, Int>()
    val levels = sortedSetOf<String>()
    val themes = sortedSetOf<String>()
    val dialogueIds = mutableSetOf<String>()
    val exerciseIds = mutableSetOf<String>()
    val storyIds = mutableSetOf<String>()
    val articleIds = mutableSetOf<String>()
    val wordIds = mutableSetOf<String>()

    fun files(folder: String): List<File> =
        File(contentDir, folder).let { dir ->
            if (dir.isDirectory) dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }.orEmpty()
            else emptyList()
        }

    fun <T> emit(folder: String, kind: String, name: String, serializer: KSerializer<T>, value: T) {
        File(outDir, folder).mkdirs()
        File(File(outDir, folder), name).writeText(outJson.encodeToString(serializer, value))
        println("$kind ${name}: ok")
    }

    // DRILLS -------------------------------------------------------------
    for (file in files("drills")) {
        val bank = DrillBankLoader.parse(file.readText())
        val compiled = bank.drills.inPublishedLevels("DRILL", { it.id }, { it.level }).map { d ->
            val withIpa = if (d.ipa.isBlank()) d.copy(ipa = g2p.phonemize(d.text)) else d
            val withVariants = withIpa.copy(
                ipaVariants = drillVariants(g2p, withIpa),
                accentIpa = withIpa.accentIpa + accents.referenceOf(withIpa.text),
                wording = (withIpa.wording + accents.sentenceWording(withIpa.id))
                    .mapValues { (_, own) -> drillWording(g2p, withIpa, own) },
            )
            if (withVariants.targets.isEmpty() && withVariants.target.isNotBlank()) {
                withVariants.copy(targets = listOf(withVariants.target))
            } else {
                withVariants
            }
        }
        val good = compiled.filter { drill ->
            // A reworded drill is still a drill: every accent's wording must keep
            // the minimal pair and the target sound, or the item is rejected.
            val problems = (listOf(null) + drill.wording.keys).flatMap { accent ->
                val result = DrillValidator.validate(if (accent == null) drill else drill.forAccent(accent), g2p)
                result.problems.map { "${if (accent == null) "" else "$accent "}${it.code}: ${it.detail}" }
            }
            if (problems.isNotEmpty()) {
                invalid++
                println("FAIL ${drill.id} (${problems.joinToString()})")
            }
            problems.isEmpty()
        }
        counts["DRILL"] = (counts["DRILL"] ?: 0) + good.size
        good.forEach { track(it.id, it.level, it.theme, levels, themes) }
        val withoutVariants = good.count { it.ipaVariants.isEmpty() }
        if (withoutVariants > 0) {
            println("DRILL ${file.name}: $withoutVariants/${good.size} without dictionary variants")
        }
        drilledWithoutVariants += withoutVariants
        emit("drills", "DRILL", file.name, DrillBank.serializer(), bank.copy(drills = good))
    }

    // WORDS --------------------------------------------------------------
    for (file in files("words")) {
        val bank = DrillBankLoader.json.decodeFromString(WordBank.serializer(), file.readText())
        val compiled = bank.words.inPublishedLevels("WORD", { it.id }, { it.level }).map { w ->
            val withIpa = if (w.ipa.isBlank()) w.copy(ipa = g2p.phonemize(w.lemma)) else w
            withIpa.copy(
                accentIpa = withIpa.accentIpa + accents.referenceOf(withIpa.lemma),
                wording = (withIpa.wording + accents.sentenceWording(withIpa.id)).mapValues { (_, own) -> own.withIpa(g2p) },
                accentText = withIpa.accentText +
                    accents.fields(withIpa.examples.indices.associate { "ex$it" to "${withIpa.id}#ex$it" }),
            )
        }
        val good = compiled.filter { report(ContentValidator.validateWord(it, pack.code), it.id) }
        counts["WORD"] = (counts["WORD"] ?: 0) + good.size
        good.forEach { track(it.id, it.level, it.theme, levels, themes) }
        good.forEach { wordIds += it.id }
        emit("words", "WORD", file.name, WordBank.serializer(), bank.copy(words = good))
    }

    // DIALOGUES ----------------------------------------------------------
    for (file in files("dialogues")) {
        val bank = DrillBankLoader.json.decodeFromString(DialogueBank.serializer(), file.readText())
        val compiled = bank.dialogues.inPublishedLevels("DIALOGUE", { it.id }, { it.level }).map { d ->
            val shown = mapOf("title" to "${d.id}#title") +
                d.turns.withIndex().associate { (i, t) -> "speaker$i" to "${d.id}#speaker:${t.speaker}" } +
                d.cast.withIndex().associate { (i, member) -> "cast$i" to "${d.id}#speaker:${member.name}" }
            d.copy(
                turns = d.turns.mapIndexed { i, t ->
                    val withIpa = if (t.ipa.isBlank()) t.copy(ipa = g2p.phonemize(t.text)) else t
                    withIpa.copy(
                        accentIpa = withIpa.accentIpa + accents.referenceOf(withIpa.text),
                        wording = (withIpa.wording + accents.sentenceWording("${d.id}#t$i"))
                            .mapValues { (_, own) -> own.withIpa(g2p) },
                    )
                },
                accentText = d.accentText + accents.fields(shown),
            )
        }
        val good = compiled.filter { report(ContentValidator.validateDialogue(it, pack.code), it.id) }
        good.forEach { dialogueIds += it.id }
        counts["DIALOGUE"] = (counts["DIALOGUE"] ?: 0) + good.size
        good.forEach { track(it.id, it.level, it.theme, levels, themes) }
        emit("dialogues", "DIALOGUE", file.name, DialogueBank.serializer(), bank.copy(dialogues = good))
    }

    // EXERCISES ----------------------------------------------------------
    for (file in files("exercises")) {
        val bank = DrillBankLoader.json.decodeFromString(ExerciseBank.serializer(), file.readText())
        val compiled = bank.exercises.inPublishedLevels("EXERCISE", { it.id }, { it.level }).map { e ->
            val shown = mapOf("prompt" to "${e.id}#prompt", "answer" to "${e.id}#answer", "instruction" to "${e.id}#instruction") +
                e.options.indices.associate { "opt$it" to "${e.id}#opt$it" }
            e.copy(accentText = e.accentText + accents.fields(shown))
        }
        // A reworded exercise is still an exercise: it must stay valid, and so
        // solvable, as every accent words it.
        val good = compiled.filter { e ->
            (listOf(e) + e.accentText.keys.map { e.forAccent(it) }).map { report(ContentValidator.validateExercise(it, pack.code), it.id) }.all { it }
        }
        good.forEach { exerciseIds += it.id }
        counts["EXERCISE"] = (counts["EXERCISE"] ?: 0) + good.size
        good.forEach { track(it.id, it.level, it.theme, levels, themes) }
        emit("exercises", "EXERCISE", file.name, ExerciseBank.serializer(), bank.copy(exercises = good))
    }

    // STORIES ------------------------------------------------------------
    for (file in files("stories")) {
        val bank = DrillBankLoader.json.decodeFromString(StoryBank.serializer(), file.readText())
        val good = bank.stories
            .inPublishedLevels("STORY", { it.id }, { it.level })
            .map {
                it.copy(
                    accentParagraphs = it.accentParagraphs + accents.paragraphs(it.id, it.paragraphs),
                    accentText = it.accentText + accents.fields(mapOf("title" to "${it.id}#title")),
                )
            }
            .filter { report(ContentValidator.validateStory(it, pack.code), it.id) }
        counts["STORY"] = (counts["STORY"] ?: 0) + good.size
        good.forEach { track(it.id, it.level, it.theme, levels, themes) }
        good.forEach { storyIds += it.id }
        emit("stories", "STORY", file.name, StoryBank.serializer(), bank.copy(stories = good))
    }

    // ARTICLES -----------------------------------------------------------
    for (file in files("articles")) {
        val bank = DrillBankLoader.json.decodeFromString(ArticleBank.serializer(), file.readText())
        val good = bank.articles
            .inPublishedLevels("ARTICLE", { it.id }, { it.level })
            .map {
                it.copy(
                    accentParagraphs = it.accentParagraphs + accents.paragraphs(it.id, it.paragraphs),
                    accentText = it.accentText + accents.fields(mapOf("title" to "${it.id}#title")),
                )
            }
            .filter { report(ContentValidator.validateArticle(it, pack.code), it.id) }
        counts["ARTICLE"] = (counts["ARTICLE"] ?: 0) + good.size
        good.forEach { track(it.id, it.level, it.theme, levels, themes) }
        good.forEach { articleIds += it.id }
        emit("articles", "ARTICLE", file.name, ArticleBank.serializer(), bank.copy(articles = good))
    }

    // LESSONS ------------------------------------------------------------
    for (file in files("lessons")) {
        val bank = DrillBankLoader.json.decodeFromString(LessonBank.serializer(), file.readText())
        val compiled = bank.lessons.inPublishedLevels("LESSON", { it.id }, { it.level }).filter { lesson ->
            val missing = (listOfNotNull(lesson.baseId) + lesson.exerciseIds + lesson.wordIds).filter { it in hiddenIds }
            if (missing.isNotEmpty()) {
                println("SKIP ${lesson.id} (uses items of an unpublished level: ${missing.joinToString()})")
                hiddenIds += lesson.id
                hiddenCounts["LESSON"] = (hiddenCounts["LESSON"] ?: 0) + 1
            }
            missing.isEmpty()
        }.map {
            it.copy(accentText = it.accentText + accents.fields(mapOf("title" to "${it.id}#title", "objective" to "${it.id}#objective")))
        }
        val good = compiled.filter {
            report(
                ContentValidator.validateLesson(it, dialogueIds, storyIds, articleIds, exerciseIds, wordIds, pack.code),
                it.id,
            )
        }
        counts["LESSON"] = (counts["LESSON"] ?: 0) + good.size
        good.forEach { track(it.id, it.level, it.theme, levels, themes) }
        emit("lessons", "LESSON", file.name, LessonBank.serializer(), bank.copy(lessons = good))
    }

    // IMAGES -------------------------------------------------------------
    // Copy the generated artwork next to the banks so a published bundle is
    // self-contained; the app loads it from /assets/images/<id>.<ext>. Both PNG
    // and SVG ride the same path, so asset versioning covers either format.
    val imagesDir = File(contentDir, "images")
    if (imagesDir.isDirectory) {
        val outImages = File(outDir, "images")
        outImages.mkdirs()
        val images = imagesDir.listFiles { f: File ->
            f.isFile && (f.extension == "png" || f.extension == "svg")
        }?.sortedBy { it.name }.orEmpty()
        images.forEach { it.copyTo(File(outImages, it.name), overwrite = true) }
        println("IMAGES ${images.size} copied -> ${outImages.path}")
    }

    // MANIFEST -----------------------------------------------------------
    // Read the authored manifest when present (it carries the localized theme
    // labels); otherwise build a minimal one, so a brand-new language ships a
    // valid catalog without hand-authoring it.
    val manifestFile = File(contentDir, "manifest.json")
    val manifest = if (manifestFile.isFile) {
        DrillBankLoader.json.decodeFromString<ContentManifest>(manifestFile.readText())
    } else {
        ContentManifest(language = pack.code)
    }
    val updated = manifest.copy(
        language = pack.code,
        // An authored level list must not advertise a level this run left out.
        levels = published?.let { keep -> manifest.levels.filter { it in keep } } ?: manifest.levels,
        banks = manifest.banks.map { ref ->
            counts[ref.kind.name]?.let { ref.copy(count = it, levels = levels.toList(), themes = themes.toList()) } ?: ref
        },
    )
    val known = updated.banks.map { it.kind.name }.toSet()
    val extra = counts.filterKeys { it !in known }.map { (kind, count) ->
        com.glosso.engine.model.ContentBankRef(
            kind = com.glosso.engine.model.ContentKind.valueOf(kind),
            path = folderFor(kind) + "/",
            version = 1,
            count = count,
            levels = levels.toList(),
            themes = themes.toList(),
        )
    }
    outDir.mkdirs()
    File(outDir, "manifest.json").writeText(
        outJson.encodeToString(ContentManifest.serializer(), updated.copy(banks = updated.banks + extra)),
    )

    val total = counts.values.sum()
    println()
    accents.problems().forEach { problem ->
        invalid++
        println("FAIL accents ($problem)")
    }
    println("Content [${pack.code}]: $total items $counts, $invalid invalid -> ${outDir.path}")
    if (published != null) println("Left out (level not in ${published.joinToString()}): $hiddenCounts")
    println("Drills with dictionary variants: ${(counts["DRILL"] ?: 0) - drilledWithoutVariants}/${counts["DRILL"] ?: 0}")
    if (invalid > 0) exitProcess(1)
    println("OK")
}

/**
 * Picks the G2P for the run: an explicit `--cmudict`/`--lexicon` wins, otherwise
 * the pack's own asset next to the content (`content/<lang>/<asset>`).
 */
private fun loadG2p(pack: LanguagePack, contentDir: File, opt: (String) -> String?): G2P? {
    opt("--cmudict")?.let { path -> File(path).takeIf { it.isFile }?.let { return CmuDictG2P.fromLines(it.readLines()) } }
    opt("--lexicon")?.let { path -> File(path).takeIf { it.isFile }?.let { return LexiconG2P.fromLines(it.readLines()) } }
    val asset = pack.g2pAsset ?: return null
    val file = listOf(File(contentDir, asset), File(contentDir.parentFile, asset)).firstOrNull { it.isFile } ?: return null
    return when (pack.g2pKind) {
        G2PKind.CMU_DICT -> CmuDictG2P.fromLines(file.readLines())
        G2PKind.LEXICON -> LexiconG2P.fromLines(file.readLines())
        G2PKind.PRECOMPUTED -> null
    }
}

/**
 * The CEFR levels named by `--levels` ("A1" or "A1,A2"), or null to publish them
 * all. An unknown level fails the run rather than silently publishing nothing.
 */
internal fun parseLevels(raw: String?): Set<String>? {
    val levels = raw?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet()
    if (levels.isNullOrEmpty()) return null
    val unknown = levels - ContentValidator.LEVELS
    require(unknown.isEmpty()) { "unknown level(s) ${unknown.joinToString()} (known: ${ContentValidator.LEVELS.sorted().joinToString()})" }
    return levels
}

private fun report(result: ContentValidator.Result, id: String): Boolean {
    if (!result.ok) {
        invalid++
        println("FAIL $id (${result.problems.joinToString { "${it.code}: ${it.detail}" }})")
    }
    return result.ok
}

/**
 * The per-word accepted readings to publish with a drill, or empty when every
 * word has a single reading (the common case, so the field costs nothing). The
 * set is [widenReferenceVariants], the same rule the server scores with, so the
 * device can score offline against exactly the cloud's reference; the CMUdict it
 * comes from is not shipped to the phone.
 *
 * A drill whose IPA does not line up word-for-word with the G2P's output gets no
 * variants (and is warned about), never a wrong set.
 */
/** The canonical reading of an accent's wording, filled like the item's own. */
private fun AccentWording.withIpa(g2p: G2P): AccentWording =
    if (text.isBlank() || ipa.isNotBlank()) this else copy(ipa = g2p.phonemize(text))

/** An accent's wording of a drill, with its reading and the dictionary variants of that reading. */
private fun drillWording(g2p: G2P, drill: DrillModel, own: AccentWording): AccentWording {
    val filled = own.withIpa(g2p)
    if (filled.text.isBlank()) return filled
    val reworded = drill.copy(text = filled.text, ipa = filled.ipa, ipaVariants = filled.ipaVariants)
    return filled.copy(ipaVariants = drillVariants(g2p, reworded))
}

internal fun drillVariants(g2p: G2P, drill: DrillModel): List<List<String>> {
    if (drill.ipaVariants.isNotEmpty()) return drill.ipaVariants
    val pronunciations = g2p.pronunciations(drill.text)
    // A lexicon G2P (French) overrides nothing, so `wordVariants` is always empty:
    // there is no widening to do and no mismatch to warn about.
    if (pronunciations.wordVariants.isEmpty()) return emptyList()
    val ipaWords = drill.ipa.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
    if (ipaWords != pronunciations.wordVariants.size) {
        println("WARN ${drill.id}: IPA has $ipaWords words but text phonemizes to ${pronunciations.wordVariants.size}; no variants published")
        return emptyList()
    }
    val widened = widenReferenceVariants(drill.ipa, pronunciations.wordVariants)
    return if (widened.any { it.size > 1 }) widened else emptyList()
}

private fun track(id: String, level: String, theme: String, levels: MutableSet<String>, themes: MutableSet<String>) {
    if (level.isNotBlank()) levels += level
    if (theme.isNotBlank()) themes += theme
}

private fun folderFor(kind: String): String = when (kind) {
    "DRILL" -> "drills"
    "WORD" -> "words"
    "STORY" -> "stories"
    "DIALOGUE" -> "dialogues"
    "ARTICLE" -> "articles"
    "EXERCISE" -> "exercises"
    "LESSON" -> "lessons"
    else -> kind.lowercase() + "s"
}
