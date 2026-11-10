package com.glosso.server.tools

import com.glosso.engine.model.ArticleModel
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.WordModel

/**
 * Publish-time validation for the content kinds that have no engine-side
 * validator (drills are checked by `DrillValidator`). Enforces the hard rules an
 * item must satisfy before a learner can see it; authoring guidance (word
 * counts, style) stays with the authoring repo, not here.
 */
object ContentValidator {

    val LEVELS = setOf("A1", "A2", "B1", "B2", "C1", "C2")

    val EXERCISE_TYPES = setOf("multiple-choice", "true-false", "fill-blank", "order", "match")

    val POS = setOf(
        "noun", "verb", "adjective", "adverb", "pronoun",
        "preposition", "conjunction", "determiner", "interjection",
    )

    data class Problem(val code: String, val detail: String)

    data class Result(val problems: List<Problem>) {
        val ok: Boolean get() = problems.isEmpty()
    }

    private fun result(failures: List<Problem?>): Result = Result(failures.filterNotNull())

    private fun problem(code: String, detail: String): Problem? = Problem(code, detail)

    private fun common(
        id: String,
        language: String,
        level: String,
        theme: String,
        expectedLanguage: String,
    ): MutableList<Problem?> =
        mutableListOf(
            if (id.isBlank()) problem("id", "blank id") else null,
            if (language != expectedLanguage) problem("language", "expected $expectedLanguage, got $language") else null,
            if (level !in LEVELS) problem("level", "unknown level $level") else null,
            if (theme.isBlank()) problem("theme", "blank theme") else null,
        )

    fun validateWord(word: WordModel, expectedLanguage: String = "en"): Result {
        val problems = common(word.id, word.language, word.level, word.theme, expectedLanguage)
        if (word.lemma.isBlank()) problems += problem("lemma", "blank lemma")
        if (word.lemma != word.lemma.lowercase()) problems += problem("lemma", "lemma not lowercase: ${word.lemma}")
        if (word.pos != null && word.pos !in POS) problems += problem("pos", "unknown pos ${word.pos}")
        if ((word.translations["es"] ?: "").isBlank()) problems += problem("translations", "missing es translation")
        if (word.examples.isEmpty()) problems += problem("examples", "no examples")
        return result(problems)
    }

    fun validateDialogue(dialogue: DialogueModel, expectedLanguage: String = "en"): Result {
        val problems = common(dialogue.id, dialogue.language, dialogue.level, dialogue.theme, expectedLanguage)
        if (dialogue.title.isBlank()) problems += problem("title", "blank title")
        if (dialogue.cast.isEmpty()) problems += problem("cast", "empty cast")
        if (dialogue.cast.size != dialogue.cast.map { it.name }.toSet().size) {
            problems += problem("cast", "duplicate cast names")
        }
        val cast = dialogue.cast.map { it.name }.toSet()
        if (dialogue.turns.isEmpty()) problems += problem("turns", "no turns")
        for ((i, turn) in dialogue.turns.withIndex()) {
            if (turn.speaker !in cast) problems += problem("turns", "turn $i speaker ${turn.speaker} not in cast")
            if (turn.text.isBlank()) problems += problem("turns", "turn $i blank text")
        }
        return result(problems)
    }

    fun validateStory(story: StoryModel, expectedLanguage: String = "en"): Result {
        val problems = common(story.id, story.language, story.level, story.theme, expectedLanguage)
        if (story.title.isBlank()) problems += problem("title", "blank title")
        if (story.paragraphs.isEmpty()) problems += problem("paragraphs", "no paragraphs")
        if (story.paragraphs.any { it.isBlank() }) problems += problem("paragraphs", "blank paragraph")
        return result(problems)
    }

    fun validateArticle(article: ArticleModel, expectedLanguage: String = "en"): Result {
        val problems = common(article.id, article.language, article.level, article.theme, expectedLanguage)
        if (article.title.isBlank()) problems += problem("title", "blank title")
        if (article.paragraphs.isEmpty()) problems += problem("paragraphs", "no paragraphs")
        if (article.paragraphs.any { it.isBlank() }) problems += problem("paragraphs", "blank paragraph")
        if (article.theme.startsWith("news-") && article.theme.removePrefix("news-") != article.category) {
            problems += problem("category", "category ${article.category} does not match theme ${article.theme}")
        }
        return result(problems)
    }

    fun validateExercise(exercise: ExerciseModel, expectedLanguage: String = "en"): Result {
        val problems = common(exercise.id, exercise.language, exercise.level, exercise.theme, expectedLanguage)
        val type = exercise.type
        if (type !in EXERCISE_TYPES) {
            problems += problem("type", "unknown type $type")
            return result(problems)
        }
        if (exercise.prompt.isBlank()) problems += problem("prompt", "blank prompt")
        when (type) {
            "multiple-choice" -> {
                if (exercise.options.size !in 3..4) {
                    problems += problem("options", "${exercise.options.size} options, expected 3-4")
                }
                if (exercise.answer !in exercise.options) problems += problem("answer", "answer not in options")
            }
            "true-false" -> {
                if (exercise.options != listOf("true", "false")) {
                    problems += problem("options", "expected exactly [true, false]")
                }
                if (exercise.answer != "true" && exercise.answer != "false") {
                    problems += problem("answer", "answer must be true or false")
                }
            }
            "fill-blank" -> {
                // No options means the learner types the word. With options it is
                // a word bank to choose from, for scripts they cannot type yet.
                val bank = exercise.options
                if (bank.isNotEmpty() && (exercise.answer !in bank || bank.size < 2 || bank.distinct().size != bank.size)) {
                    problems += problem("options", "a word bank must be distinct words that include the answer")
                }
                if (exercise.answer.trim().split(" ").size != 1) problems += problem("answer", "must be one word")
                if (!exercise.prompt.contains("___")) problems += problem("prompt", "missing ___ gap")
            }
            "order" -> {
                if (exercise.options.isNotEmpty()) problems += problem("options", "must be empty")
                if (normalizedWords(exercise.prompt).sorted() != normalizedWords(exercise.answer).sorted()) {
                    problems += problem("answer", "prompt is not a permutation of the answer")
                }
            }
            "match" -> {
                if (exercise.answer.isNotEmpty()) problems += problem("answer", "match answer must be empty")
                if (exercise.options.isEmpty()) problems += problem("options", "match needs pair options")
            }
        }
        return result(problems)
    }

    fun validateLesson(
        lesson: LessonModel,
        dialogueIds: Set<String>,
        storyIds: Set<String>,
        articleIds: Set<String>,
        exerciseIds: Set<String>,
        wordIds: Set<String>,
        expectedLanguage: String = "en",
    ): Result {
        val problems = common(lesson.id, lesson.language, lesson.level, lesson.theme, expectedLanguage)
        if (lesson.title.isBlank()) problems += problem("title", "blank title")
        if (lesson.objective.isBlank()) problems += problem("objective", "blank objective")
        val bases = listOfNotNull(
            lesson.dialogueId?.takeIf { it.isNotBlank() },
            lesson.storyId?.takeIf { it.isNotBlank() },
            lesson.articleId?.takeIf { it.isNotBlank() },
        )
        if (bases.isEmpty()) {
            problems += problem("base", "lesson needs one of dialogueId, storyId or articleId")
        } else if (bases.size > 1) {
            problems += problem("base", "lesson has ${bases.size} bases, expected exactly one")
        } else {
            val baseId = bases.single()
            val known = baseId in dialogueIds || baseId in storyIds || baseId in articleIds
            if (!known) problems += problem("base", "unknown base item $baseId")
        }
        if (lesson.exerciseIds.size !in 2..6) {
            problems += problem("exerciseIds", "${lesson.exerciseIds.size} exercises, expected 2-6")
        }
        for (exerciseId in lesson.exerciseIds) {
            if (exerciseId !in exerciseIds) problems += problem("exerciseIds", "unknown exercise $exerciseId")
        }
        for (wordId in lesson.wordIds) {
            if (wordId !in wordIds) problems += problem("wordIds", "unknown word $wordId")
        }
        for (target in lesson.drillTargets) {
            if (target.isBlank() || target.contains(' ')) {
                problems += problem("drillTargets", "invalid drill target '$target'")
            }
        }
        problems += validateNotes(lesson)
        return result(problems)
    }

    /** Most notes a lesson opens with: the context step is a primer, not a chapter. */
    const val MAX_NOTES = 4

    /** Longest note body, in words, as authored. */
    const val MAX_NOTE_WORDS = 90

    /** Longest translated note body: French and German run about a fifth longer than Spanish. */
    const val MAX_TRANSLATED_NOTE_WORDS = 110

    /** The base interface language notes are written in. */
    private const val NOTE_BASE_LANGUAGE = "es"

    /**
     * The culture notes of a lesson: short, complete and in every interface
     * language the lesson itself is translated into. The app never shows a
     * meaning in another language than the interface's, so an example without
     * that translation would show no meaning at all.
     */
    private fun validateNotes(lesson: LessonModel): List<Problem?> {
        val problems = mutableListOf<Problem?>()
        if (lesson.notes.size > MAX_NOTES) {
            problems += problem("notes", "${lesson.notes.size} notes, at most $MAX_NOTES")
        }
        val uiLanguages = lesson.l10n.keys.filter { it.isNotBlank() }.toSet()
        for ((i, note) in lesson.notes.withIndex()) {
            if (note.title.isBlank()) problems += problem("notes", "note $i blank title")
            if (note.body.isBlank()) problems += problem("notes", "note $i blank body")
            if (wordCount(note.body) > MAX_NOTE_WORDS) {
                problems += problem("notes", "note $i body has ${wordCount(note.body)} words, at most $MAX_NOTE_WORDS")
            }
            for (language in uiLanguages) {
                val own = note.l10n[language]
                if (own == null || own["title"].isNullOrBlank() || own["body"].isNullOrBlank()) {
                    problems += problem("notes", "note $i has no $language title and body")
                } else if (wordCount(own.getValue("body")) > MAX_TRANSLATED_NOTE_WORDS) {
                    problems += problem("notes", "note $i $language body over $MAX_TRANSLATED_NOTE_WORDS words")
                }
            }
            for ((j, example) in note.examples.withIndex()) {
                if (example.text.isBlank()) problems += problem("notes", "note $i example $j blank text")
                for (language in uiLanguages + NOTE_BASE_LANGUAGE) {
                    if (example.translations[language].isNullOrBlank()) {
                        problems += problem("notes", "note $i example $j has no $language translation")
                    }
                }
            }
        }
        return problems
    }

    private fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

    private fun normalizedWords(text: String): List<String> =
        text.split(" ")
            .map { it.trim { ch -> !ch.isLetterOrDigit() && ch != '\'' }.lowercase() }
            .filter { it.isNotEmpty() }
}
