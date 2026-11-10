package com.glosso.engine.model

/**
 * The wording of one item in an accent other than the one it was authored in.
 *
 * The course is written in one variety (British English) and read by voices of
 * several accents. Where the authored wording is audibly that variety ("lorry",
 * "have got", "at the weekend"), the accent carries its own text, so an American
 * voice never reads British English and the learner sees the words they hear.
 * Only the scorable fields are here: translations, level and theme do not change
 * with the accent. What is shown but never scored (a title, a character's name,
 * an exercise, a word's examples) is reworded through `accentText` instead, a
 * plain field-to-text map per accent.
 */
@kotlinx.serialization.Serializable
data class AccentWording(
    /** The sentence, the dialogue line or the lemma as this accent says it. */
    val text: String = "",
    /** Canonical reference of [text]; filled at publish time like the item's own. */
    val ipa: String = "",
    val ipaVariants: List<List<String>> = emptyList(),
    val accentIpa: Map<String, String> = emptyMap(),
    val displayIpa: String = "",
    /** A drill's backward build-up for this wording; empty when it has none. */
    val buildup: List<String> = emptyList(),
)

/** Key under which an item that was reworded keeps what it was authored as. */
private const val AUTHORED = ""

/**
 * The item as [accent] words it. An accent with no wording of its own gets the
 * authored item, so the authored text is always the fallback. The reference
 * audio is untouched: its clips are already keyed by voice.
 *
 * [authored] is the accent the bank was written in (the pack's default). The
 * display IPA is that accent's textbook transcription, so under any other accent
 * it is dropped and the app shows the dictionary reading instead of a British
 * transcription under an American voice.
 *
 * It is reversible: a changed item remembers how it was authored, so asking for
 * another accent later (the learner changes it in Settings, or opens a lesson
 * downloaded under the other accent) gives the right text again.
 */
fun DrillModel.forAccent(accent: String, authored: String? = null): DrillModel {
    val mine = AccentWording(text, ipa, ipaVariants, accentIpa, displayIpa, buildup)
    val own = wording.pick(accent, mine, authored) ?: return this
    if (own == mine) return this
    return copy(
        text = own.text,
        ipa = own.ipa,
        ipaVariants = own.ipaVariants,
        accentIpa = own.accentIpa,
        displayIpa = own.displayIpa,
        // The authored chunks spell the authored wording. An accent's own
        // wording has none unless it brings them, and then the app simply does
        // not offer the build-up; going back to the authored wording restores it.
        buildup = if (own.text == text) buildup else own.buildup,
        wording = wording + (AUTHORED to (wording[AUTHORED] ?: mine)),
    )
}

fun WordModel.forAccent(accent: String, authored: String? = null): WordModel {
    val worded = wordedFor(accent, authored)
    val mine = worded.examples.fields("ex")
    val own = worded.accentText.pick(accent, mine) ?: return worded
    return worded.copy(examples = worded.examples.indices.map { own.getValue("ex$it") }, accentText = worded.accentText.remembering(mine))
}

private fun WordModel.wordedFor(accent: String, authored: String?): WordModel {
    val mine = AccentWording(text = lemma, ipa = ipa, accentIpa = accentIpa, displayIpa = displayIpa)
    val own = wording.pick(accent, mine, authored) ?: return this
    if (own == mine) return this
    return copy(
        lemma = own.text,
        ipa = own.ipa,
        accentIpa = own.accentIpa,
        displayIpa = own.displayIpa,
        wording = wording + (AUTHORED to (wording[AUTHORED] ?: mine)),
    )
}

fun DialogueTurn.forAccent(accent: String, authored: String? = null): DialogueTurn {
    val mine = AccentWording(text = text, ipa = ipa, accentIpa = accentIpa, displayIpa = displayIpa)
    val own = wording.pick(accent, mine, authored) ?: return this
    if (own == mine) return this
    return copy(
        text = own.text,
        ipa = own.ipa,
        accentIpa = own.accentIpa,
        displayIpa = own.displayIpa,
        wording = wording + (AUTHORED to (wording[AUTHORED] ?: mine)),
    )
}

fun DialogueModel.forAccent(accent: String, authored: String? = null): DialogueModel {
    val turns = turns.map { it.forAccent(accent, authored) }
    val worded = if (turns == this.turns) this else copy(turns = turns)
    val mine = mapOf(TITLE to title) + turns.map { it.speaker }.fields("speaker") + cast.map { it.name }.fields("cast")
    val own = accentText.pick(accent, mine) ?: return worded
    return worded.copy(
        title = own.getValue(TITLE),
        turns = turns.mapIndexed { i, turn -> turn.copy(speaker = own.getValue("speaker$i")) },
        cast = cast.mapIndexed { i, member -> member.copy(name = own.getValue("cast$i")) },
        accentText = accentText.remembering(mine),
    )
}

/** A list that does not line up with [StoryModel.paragraphs] is ignored: the audio is per paragraph. */
fun StoryModel.forAccent(accent: String): StoryModel {
    val worded = paragraphsFor(accent)
    val mine = mapOf(TITLE to title)
    val own = accentText.pick(accent, mine) ?: return worded
    return worded.copy(title = own.getValue(TITLE), accentText = accentText.remembering(mine))
}

private fun StoryModel.paragraphsFor(accent: String): StoryModel {
    val own = accentParagraphs.pick(accent, paragraphs.size) ?: return this
    if (own == paragraphs) return this
    return copy(paragraphs = own, accentParagraphs = accentParagraphs + (AUTHORED to (accentParagraphs[AUTHORED] ?: paragraphs)))
}

fun ArticleModel.forAccent(accent: String): ArticleModel {
    val worded = paragraphsFor(accent)
    val mine = mapOf(TITLE to title)
    val own = accentText.pick(accent, mine) ?: return worded
    return worded.copy(title = own.getValue(TITLE), accentText = accentText.remembering(mine))
}

private fun ArticleModel.paragraphsFor(accent: String): ArticleModel {
    val own = accentParagraphs.pick(accent, paragraphs.size) ?: return this
    if (own == paragraphs) return this
    return copy(paragraphs = own, accentParagraphs = accentParagraphs + (AUTHORED to (accentParagraphs[AUTHORED] ?: paragraphs)))
}

/** The answer is reworded together with the options, so a task stays solvable under every accent. */
fun ExerciseModel.forAccent(accent: String): ExerciseModel {
    val mine = mapOf(PROMPT to prompt, ANSWER to answer) + options.fields("opt") +
        (instruction?.let { mapOf(INSTRUCTION to it) } ?: emptyMap())
    val own = accentText.pick(accent, mine) ?: return this
    return copy(
        prompt = own.getValue(PROMPT),
        answer = own.getValue(ANSWER),
        options = options.indices.map { own.getValue("opt$it") },
        instruction = instruction?.let { own.getValue(INSTRUCTION) },
        accentText = accentText.remembering(mine),
    )
}

fun LessonModel.forAccent(accent: String): LessonModel {
    val mine = mapOf(TITLE to title, OBJECTIVE to objective)
    val own = accentText.pick(accent, mine) ?: return this
    return copy(title = own.getValue(TITLE), objective = own.getValue(OBJECTIVE), accentText = accentText.remembering(mine))
}

private const val TITLE = "title"
private const val OBJECTIVE = "objective"
private const val PROMPT = "prompt"
private const val ANSWER = "answer"
private const val INSTRUCTION = "instruction"

private fun List<String>.fields(prefix: String): Map<String, String> = withIndex().associate { (i, text) -> "$prefix$i" to text }

/**
 * Every field of [mine] as [accent] words it, or null when that is what the item
 * already shows. A field the accent does not reword keeps its authored text, and
 * a key that names no field of the item is ignored.
 */
private fun Map<String, Map<String, String>>.pick(accent: String, mine: Map<String, String>): Map<String, String>? {
    val own = this[accent]?.takeIf { accent != AUTHORED }.orEmpty().filter { (field, text) -> field in mine && text.isNotBlank() }
    val original = this[AUTHORED]?.takeIf { it.keys == mine.keys }
    if (own.isEmpty() && original == null) return null
    return ((original ?: mine) + own).takeIf { it != mine }
}

private fun Map<String, Map<String, String>>.remembering(mine: Map<String, String>): Map<String, Map<String, String>> =
    this + (AUTHORED to (this[AUTHORED]?.takeIf { it.keys == mine.keys } ?: mine))

/**
 * The accent's own wording; else the authored one once the item was changed;
 * else, under an accent the bank was not written in, the item [mine] without its
 * textbook transcription.
 */
private fun Map<String, AccentWording>.pick(accent: String, mine: AccentWording, authored: String?): AccentWording? {
    this[accent]?.takeIf { accent != AUTHORED && it.text.isNotBlank() }?.let { return it }
    val original = this[AUTHORED] ?: mine
    val foreign = authored != null && !accent.equals(authored, ignoreCase = true)
    return when {
        foreign && original.displayIpa.isNotBlank() -> original.copy(displayIpa = "")
        this[AUTHORED] != null -> original
        else -> null
    }
}

private fun Map<String, List<String>>.pick(accent: String, size: Int): List<String>? =
    (this[accent]?.takeIf { accent != AUTHORED && it.size == size }) ?: this[AUTHORED]
