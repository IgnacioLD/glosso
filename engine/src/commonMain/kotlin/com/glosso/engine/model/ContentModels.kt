package com.glosso.engine.model

import kotlinx.serialization.Serializable

/**
 * What a content bank holds. The platform is content-agnostic: every kind shares
 * the same [ContentMeta] envelope, the same theme/level vocabulary and the same
 * versioned delivery, so drills, stories, dialogues, lessons and articles all
 * arrive through one pipeline and one manifest.
 */
@Serializable
enum class ContentKind { WORD, DRILL, DIALOGUE, LESSON, STORY, ARTICLE, EXERCISE }

/** A topic, with its display name per UI language. */
@Serializable
data class ThemeModel(
    val id: String,
    val labels: Map<String, String> = emptyMap(),
)

/** One downloadable bank in the content manifest. */
@Serializable
data class ContentBankRef(
    val kind: ContentKind,
    val path: String,
    val version: Int,
    val count: Int = 0,
    val levels: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    /**
     * Total bytes of the assets this bank's items reference, from the files the
     * publisher measured. 0 when it is not published (an older manifest).
     */
    val bytes: Long = 0,
)

/**
 * Catalog of everything the app can download. Versioned per bank so a weekly
 * release only ships what changed: the app fetches this, diffs versions and pulls
 * just the new or updated banks.
 */
@Serializable
data class ContentManifest(
    val version: Int = 1,
    val language: String = "en",
    val publishedAt: String = "",
    /**
     * Opaque token that changes whenever the served content changes. The app
     * appends it to asset URLs so a rebuilt catalogue is not masked by a stale
     * CDN/HTTP cache.
     */
    val assetVersion: String = "",
    /**
     * Every study level the catalog currently publishes, e.g. `["A1", "A2"]`.
     * The app derives its level picker from this, so a level added by the server
     * shows up without shipping a new build. Empty means "derive from content".
     */
    val levels: List<String> = emptyList(),
    val themes: List<ThemeModel> = emptyList(),
    val banks: List<ContentBankRef> = emptyList(),
    /**
     * Byte size of every downloadable asset for this language, keyed by its
     * asset-relative path ("audio/x.ogg", "images/y.svg"). It lets a client add
     * up a lesson's size locally instead of probing each file over the network,
     * which was the slow part of "how big is this lesson". Empty means the
     * server does not publish sizes and the client falls back to probing.
     */
    val assets: Map<String, Long> = emptyMap(),
) {
    fun bank(kind: ContentKind): ContentBankRef? = banks.firstOrNull { it.kind == kind }

    /** Total size of [paths] when every one is known, else null. */
    fun sizeOf(paths: Collection<String>): Long? {
        if (paths.isEmpty()) return 0L
        var total = 0L
        for (path in paths) {
            val size = assets[path] ?: return null
            total += size
        }
        return total
    }
}

/**
 * Fields every content item carries, so the app can filter, schedule and target
 * practice without knowing the concrete kind.
 */
interface ContentMeta {
    val id: String
    val language: String
    val level: String
    val theme: String
    /** Sounds, words or grammar points this item trains. */
    val targets: List<String>
}

/**
 * A pronunciation drill: a minimal pair ([focus], target word first) carried by a
 * short sentence ([text]) that repeats the target sound. Authored and validated
 * offline, never generated at runtime.
 */
@Serializable
data class DrillModel(
    override val id: String,
    override val language: String = "en",
    override val level: String = "A1",
    override val theme: String = "",
    override val targets: List<String> = emptyList(),
    val target: String,
    val contrast: String? = null,
    val ruleId: String? = null,
    val focus: List<String> = emptyList(),
    val text: String,
    val ipa: String = "",
    /**
     * Every IPA reading that counts as correct, one entry per word of [text] and
     * parallel to [ipa] (the shape
     * [com.glosso.engine.g2p.widenReferenceVariants] returns). A word that does not
     * vary is left empty and the scorer falls back to the [ipa] word for it; a word
     * with several readings (CMUdict lists both the American flap `TH ER1 D IY2` and
     * the British `/t/` `TH ER1 T IY2` for "thirty") lists them all, canonical
     * first, so the offline scorer can accept either instead of penalising a correct
     * reading. The whole field is empty when no word varies.
     *
     * It is published with the content so the device can widen the reference
     * exactly like the server does; the CMUdict it derives from does not ship to
     * the phone.
     */
    val ipaVariants: List<List<String>> = emptyList(),
    /**
     * Reference audio per voice key ("en-US-female", "en-GB-male"...); the ":slow"
     * suffix is the slow speed. The app plays the one matching the learner's
     * settings, so accent and gender are chosen without re-downloading content.
     */
    val audio: Map<String, String> = emptyMap(),
    val translations: Map<String, String> = emptyMap(),
    /** Situational prompt that elicits [text] from memory, per UI language. */
    val cue: Map<String, String> = emptyMap(),
    /** Backward buildup: [text] cut into chunks, longest first, to drill rhythm. */
    val buildup: List<String> = emptyList(),
    /**
     * IPA written the way a learner's dictionary does, for **display** only. The
     * [ipa] field stays the reference the scorer uses; this one is what the app
     * shows when the learner turns phonetics on. It is built at publish time by
     * `tools/content/textbook_ipa.py` and is never sent to the scorer, so the two
     * representations can differ freely. Empty means "show [ipa]".
     */
    val displayIpa: String = "",
    /**
     * Whole-sentence references of [text] in the convention the acoustic model
     * transcribes in, keyed by accent ("en-GB"). Built at publish time by
     * `tools/content/accent_reference.py` and sent to the scorer as
     * `ScoreRequest.accentIpa`; [ipa] stays the canonical dictionary reading. See
     * docs/MULTI-ACCENT.md.
     */
    val accentIpa: Map<String, String> = emptyMap(),
    /** What an accent says instead of the authored wording; see [AccentWording]. */
    val wording: Map<String, AccentWording> = emptyMap(),
) : ContentMeta

/** A lexicon entry. */
@Serializable
data class WordModel(
    override val id: String,
    override val language: String = "en",
    override val level: String = "A1",
    override val theme: String = "",
    override val targets: List<String> = emptyList(),
    val lemma: String,
    val ipa: String = "",
    val pos: String? = null,
    val translations: Map<String, String> = emptyMap(),
    val examples: List<String> = emptyList(),
    val frequencyRank: Int? = null,
    /** Reference audio of the lemma per voice key ("en-US-female", ...). */
    val audio: Map<String, String> = emptyMap(),
    /**
     * IPA written the way a learner's dictionary does, for **display** only; see
     * [DrillModel.displayIpa]. Empty means "show [ipa]".
     */
    val displayIpa: String = "",
    /**
     * Whole-sentence references of [lemma] in the convention the acoustic model
     * transcribes in, keyed by accent ("en-GB"). Built at publish time by
     * `tools/content/accent_reference.py` and sent to the scorer as
     * `ScoreRequest.accentIpa`; [ipa] stays the canonical dictionary reading. See
     * docs/MULTI-ACCENT.md.
     */
    val accentIpa: Map<String, String> = emptyMap(),
    /** What an accent says instead of the authored wording; see [AccentWording]. */
    val wording: Map<String, AccentWording> = emptyMap(),
    /**
     * The display fields an accent words differently, keyed by accent and then by
     * field (`ex<i>` for the example at index i). See [AccentWording].
     */
    val accentText: Map<String, Map<String, String>> = emptyMap(),
) : ContentMeta

@Serializable
data class DialogueTurn(
    val speaker: String,
    val text: String,
    val ipa: String = "",
    val translations: Map<String, String> = emptyMap(),
    /** Reference audio for this turn per voice key ("en-US-female", ...). */
    val audio: Map<String, String> = emptyMap(),
    /** IPA written the way a learner's dictionary does, for display only; see [DrillModel.displayIpa]. */
    val displayIpa: String = "",
    /**
     * Whole-sentence references of [text] in the convention the acoustic model
     * transcribes in, keyed by accent ("en-GB"). Built at publish time by
     * `tools/content/accent_reference.py` and sent to the scorer as
     * `ScoreRequest.accentIpa`; [ipa] stays the canonical dictionary reading. See
     * docs/MULTI-ACCENT.md.
     */
    val accentIpa: Map<String, String> = emptyMap(),
    /** What an accent says instead of the authored wording; see [AccentWording]. */
    val wording: Map<String, AccentWording> = emptyMap(),
)

/** A character in a dialogue; the accent comes from the learner's settings. */
@Serializable
data class DialogueSpeaker(
    val name: String,
    val gender: VoiceGender = VoiceGender.MALE,
)

/** A short conversation, the atom a lesson is built around. */
@Serializable
data class DialogueModel(
    override val id: String,
    override val language: String = "en",
    override val level: String = "A1",
    override val theme: String = "",
    override val targets: List<String> = emptyList(),
    val title: String,
    val turns: List<DialogueTurn> = emptyList(),
    /** Who speaks in [turns]; each is read by a voice of its gender. */
    val cast: List<DialogueSpeaker> = emptyList(),
    val image: String? = null,
    val imagePrompt: String? = null,
    /**
     * The display fields an accent words differently, keyed by accent and then by
     * field (`title`, `speaker<i>` for the speaker of turn i, `cast<i>` for the cast name at index i). See [AccentWording].
     */
    val accentText: Map<String, Map<String, String>> = emptyMap(),
) : ContentMeta

/** A graded reading passage (Glosso Stories). */
@Serializable
data class StoryModel(
    override val id: String,
    override val language: String = "en",
    override val level: String = "A1",
    override val theme: String = "",
    override val targets: List<String> = emptyList(),
    val title: String,
    val paragraphs: List<String> = emptyList(),
    val ipa: String = "",
    val translations: Map<String, String> = emptyMap(),
    /** Reference audio per paragraph, each keyed by voice ("en-GB-male", ...). */
    val paragraphAudio: List<Map<String, String>> = emptyList(),
    /** Asset-relative image ("images/en.story.family.001.png"), when the story has one. */
    val image: String? = null,
    /** Prompt used to generate [image]; kept so it can be regenerated consistently. */
    val imagePrompt: String? = null,
    /** IPA written the way a learner's dictionary does, for display only; see [DrillModel.displayIpa]. */
    val displayIpa: String = "",
    /**
     * The paragraphs as an accent words them ("en-US"), the whole list, parallel
     * to [paragraphs]. Empty for an accent that reads the authored text.
     */
    val accentParagraphs: Map<String, List<String>> = emptyMap(),
    /**
     * The display fields an accent words differently, keyed by accent and then by
     * field (`title`). See [AccentWording].
     */
    val accentText: Map<String, Map<String, String>> = emptyMap(),
) : ContentMeta

/** A fictional news article designed to teach (Glosso News). */
@Serializable
data class ArticleModel(
    override val id: String,
    override val language: String = "en",
    override val level: String = "A1",
    override val theme: String = "",
    override val targets: List<String> = emptyList(),
    val title: String,
    val category: String = "",
    val paragraphs: List<String> = emptyList(),
    val translations: Map<String, String> = emptyMap(),
    /** Reference audio per paragraph, each keyed by voice ("en-GB-male", ...). */
    val paragraphAudio: List<Map<String, String>> = emptyList(),
    val image: String? = null,
    val imagePrompt: String? = null,
    /**
     * The paragraphs as an accent words them ("en-US"), the whole list, parallel
     * to [paragraphs]. Empty for an accent that reads the authored text.
     */
    val accentParagraphs: Map<String, List<String>> = emptyMap(),
    /**
     * The display fields an accent words differently, keyed by accent and then by
     * field (`title`). See [AccentWording].
     */
    val accentText: Map<String, Map<String, String>> = emptyMap(),
) : ContentMeta

/** A graded, self-contained task. */
@Serializable
data class ExerciseModel(
    override val id: String,
    override val language: String = "en",
    override val level: String = "A1",
    override val theme: String = "",
    override val targets: List<String> = emptyList(),
    val type: String,
    val prompt: String,
    val options: List<String> = emptyList(),
    val answer: String = "",
    /**
     * Optional per-item instruction shown above the task. When present the app
     * prefers it over the built-in wording for [type], which lets the content
     * team author new task types without an app release.
     */
    val instruction: String? = null,
    /** When set, this exercise belongs to that story (comprehension, vocab...). */
    val storyId: String? = null,
    val image: String? = null,
    val imagePrompt: String? = null,
    /**
     * The display fields an accent words differently, keyed by accent and then by
     * field (`prompt`, `answer`, `instruction`, `opt<i>` for the option at index i). See [AccentWording].
     */
    val accentText: Map<String, Map<String, String>> = emptyMap(),
    /**
     * The fields written in the learner's language rather than the one taught, in
     * other interface languages: keyed by language and then by field (`prompt`,
     * `opt<i>`). A matching exercise pairs each word with its meaning, and the
     * meaning is in the learner's language. See [localized].
     */
    val l10n: Map<String, Map<String, String>> = emptyMap(),
) : ContentMeta

/**
 * A unit of study: one base item the learner reads or listens to first (a
 * dialogue, a story or a news article), then exercises about that base, then
 * pronunciation drills for the lesson theme. Exactly one of [dialogueId],
 * [storyId], [articleId] is set. [drillTargets] names the sounds the lesson
 * trains, so drill selection can intersect the learner's weak phonemes with the
 * lesson's sounds instead of the whole inventory.
 */
@Serializable
data class LessonModel(
    override val id: String,
    override val language: String = "en",
    override val level: String = "A1",
    override val theme: String = "",
    override val targets: List<String> = emptyList(),
    val title: String,
    val objective: String = "",
    val dialogueId: String? = null,
    val storyId: String? = null,
    val articleId: String? = null,
    val exerciseIds: List<String> = emptyList(),
    val wordIds: List<String> = emptyList(),
    val drillTargets: List<String> = emptyList(),
    /**
     * The display fields an accent words differently, keyed by accent and then by
     * field (`title`, `objective`). See [AccentWording].
     */
    val accentText: Map<String, Map<String, String>> = emptyMap(),
    /**
     * [title] and [objective] in other interface languages, keyed by language and
     * then by field. For courses whose lesson titles are written for the learner
     * rather than in the language taught (Russian: a beginner cannot read a
     * Cyrillic title). See [localized].
     */
    val l10n: Map<String, Map<String, String>> = emptyMap(),
    /**
     * Culture, etiquette, register or language notes shown before the base, so
     * the learner knows how the situation works before hearing it. Written in
     * the base interface language (Spanish) with [LessonNote.l10n] for the rest.
     */
    val notes: List<LessonNote> = emptyList(),
) : ContentMeta {
    /** The id of whichever base item this lesson opens with. */
    val baseId: String? get() = dialogueId ?: storyId ?: articleId
}

/** What a [LessonNote] is about; the app names it with a small label. */
@Serializable
enum class NoteKind { CULTURE, ETIQUETTE, REGISTER, LANGUAGE }

/**
 * One phrase that illustrates a [LessonNote], in the language taught (stress
 * marks included, as a reading aid), with its meaning per interface language.
 * [wrong] marks what is not said, shown as such rather than as a model.
 */
@Serializable
data class NoteExample(
    val text: String,
    val translations: Map<String, String> = emptyMap(),
    val wrong: Boolean = false,
)

/**
 * A short note on how the situation of a lesson works in the culture of the
 * language: [title] and [body] in the base interface language, [l10n] keyed by
 * interface language and then by field (`title`, `body`). See [localized].
 */
@Serializable
data class LessonNote(
    val kind: NoteKind,
    val title: String,
    val body: String,
    val examples: List<NoteExample> = emptyList(),
    val l10n: Map<String, Map<String, String>> = emptyMap(),
)

/** The note with its title and body in [uiLanguage]; see [LessonModel.localized]. */
fun LessonNote.localized(uiLanguage: String): LessonNote {
    if (l10n.isEmpty()) return this
    val authored = l10n[L10N_AUTHORED] ?: mapOf(L10N_TITLE to title, L10N_BODY to body)
    val own = authored.inLanguage(l10n[uiLanguage])
    return copy(
        title = own.getValue(L10N_TITLE),
        body = own.getValue(L10N_BODY),
        l10n = l10n + (L10N_AUTHORED to authored),
    )
}

/**
 * The lesson with its title and objective in [uiLanguage], or as authored when
 * it carries none for that language. The authored text is kept under a reserved
 * key, so a stored lesson can be read again in another language.
 */
fun LessonModel.localized(uiLanguage: String): LessonModel {
    val localNotes = notes.map { it.localized(uiLanguage) }
    if (l10n.isEmpty()) return if (notes.isEmpty()) this else copy(notes = localNotes)
    val authored = l10n[L10N_AUTHORED] ?: mapOf(L10N_TITLE to title, L10N_OBJECTIVE to objective)
    val own = authored.inLanguage(l10n[uiLanguage])
    return copy(
        title = own.getValue(L10N_TITLE),
        objective = own.getValue(L10N_OBJECTIVE),
        l10n = l10n + (L10N_AUTHORED to authored),
        notes = localNotes,
    )
}

/**
 * The exercise with its prompt, options and instruction in [uiLanguage]; see
 * [LessonModel.localized]. An exercise without an authored instruction keeps
 * none, so the app's built-in wording for its type still applies.
 */
fun ExerciseModel.localized(uiLanguage: String): ExerciseModel {
    if (l10n.isEmpty()) return this
    val authored = l10n[L10N_AUTHORED]
        ?: (
            mapOf(L10N_PROMPT to prompt) +
                options.withIndex().associate { (i, text) -> "$L10N_OPTION$i" to text } +
                listOfNotNull(instruction?.let { L10N_INSTRUCTION to it })
            )
    val own = authored.inLanguage(l10n[uiLanguage])
    val localizedOptions = options.indices.map { own.getValue("$L10N_OPTION$it") }
    // A choice answer is one of the options: it follows that option into the
    // learner's language, or the card would compare a translated pick to the original.
    // Its index is the same in every language, so localizing again stays right.
    val answerIndex = options.indexOf(answer)
    return copy(
        prompt = own.getValue(L10N_PROMPT),
        options = localizedOptions,
        answer = if (answerIndex >= 0) localizedOptions[answerIndex] else answer,
        // An authored map stored before instructions were localized has no such
        // key: keep the instruction as it is rather than dropping it.
        instruction = own[L10N_INSTRUCTION] ?: instruction,
        l10n = l10n + (L10N_AUTHORED to authored),
    )
}

/** The authored fields with every one [translated] carries, non-blank, taken from it. */
private fun Map<String, String>.inLanguage(translated: Map<String, String>?): Map<String, String> =
    mapValues { (field, text) -> translated?.get(field)?.takeIf { it.isNotBlank() } ?: text }

private const val L10N_AUTHORED = ""
private const val L10N_TITLE = "title"
private const val L10N_OBJECTIVE = "objective"
private const val L10N_PROMPT = "prompt"
private const val L10N_OPTION = "opt"
private const val L10N_INSTRUCTION = "instruction"
private const val L10N_BODY = "body"

/** The IPA to show the learner: the textbook form when present, else the reference. */
val DrillModel.displayPhonetic: String get() = displayIpa.ifBlank { ipa }

/** The IPA to show the learner: the textbook form when present, else the reference. */
val WordModel.displayPhonetic: String get() = displayIpa.ifBlank { ipa }

/** The IPA to show the learner: the textbook form when present, else the reference. */
val DialogueTurn.displayPhonetic: String get() = displayIpa.ifBlank { ipa }

/** The IPA to show the learner: the textbook form when present, else the reference. */
val StoryModel.displayPhonetic: String get() = displayIpa.ifBlank { ipa }

@Serializable
data class WordBank(val version: Int = 1, val language: String = "en", val words: List<WordModel> = emptyList())

@Serializable
data class DrillBank(val version: Int = 1, val language: String = "en", val drills: List<DrillModel> = emptyList())

@Serializable
data class DialogueBank(val version: Int = 1, val language: String = "en", val dialogues: List<DialogueModel> = emptyList())

@Serializable
data class StoryBank(val version: Int = 1, val language: String = "en", val stories: List<StoryModel> = emptyList())

@Serializable
data class ArticleBank(val version: Int = 1, val language: String = "en", val articles: List<ArticleModel> = emptyList())

@Serializable
data class ExerciseBank(val version: Int = 1, val language: String = "en", val exercises: List<ExerciseModel> = emptyList())

@Serializable
data class LessonBank(val version: Int = 1, val language: String = "en", val lessons: List<LessonModel> = emptyList())

/** The drills chosen for one learner, worst sound first, plus themes to offer. */
@Serializable
data class DrillPlanModel(
    val drills: List<DrillModel> = emptyList(),
    /** Themes that have drills for this learner's weak sounds, for the picker. */
    val themes: List<ThemeModel> = emptyList(),
)
