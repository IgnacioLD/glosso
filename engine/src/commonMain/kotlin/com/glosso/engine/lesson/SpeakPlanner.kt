package com.glosso.engine.lesson

import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DialogueTurn

/**
 * One line the learner says from memory in the "your turn" step: the turn to
 * produce and, when another character speaks right before it, the line that
 * prompts it.
 */
data class SpeakLine(val turn: Int, val partner: Int?)

/**
 * Chooses the lines of a dialogue the learner plays after studying it. The
 * learner takes one character and says that character's lines with the text
 * hidden: production from recall, not reading aloud. The prompt is the line in
 * the learner's own language; where the dialogue has no translation into the
 * interface language (an English interface on the English course, say), the
 * prompt is the other character's line alone, so only lines that answer one are
 * asked for. It uses only what a dialogue already carries (text, reference IPA,
 * the speaker's clip and a translation), so no content or audio is authored for it.
 */
object SpeakPlanner {

    const val MAX_LINES = 4
    const val MIN_WORDS = 2
    const val MAX_WORDS = 10

    /**
     * The lines to say, in dialogue order, or empty when the dialogue has none
     * that can be prompted and scored.
     */
    fun plan(dialogue: DialogueModel, uiLanguage: String): List<SpeakLine> {
        val speakable = dialogue.turns.withIndex().filter { (_, turn) -> turn.speakable() }
        val cued = speakable.filter { (_, turn) -> cue(turn, uiLanguage) != null }
        val eligible = cued.ifEmpty {
            speakable.filter { (index, turn) ->
                dialogue.turns.getOrNull(index - 1)?.let { it.speaker != turn.speaker } == true
            }
        }
        if (eligible.isEmpty()) return emptyList()
        val opener = dialogue.turns.firstOrNull()?.speaker
        // The character with most lines to say; on a tie, the one who answers
        // rather than the one who opens the conversation.
        val role = eligible.groupBy { it.value.speaker }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<IndexedValue<DialogueTurn>>>> { it.value.size }
                .thenBy { if (it.key == opener) 1 else 0 })
            .first().key
        return eligible.filter { it.value.speaker == role }.take(MAX_LINES).map { (index, _) ->
            val before = dialogue.turns.getOrNull(index - 1)
            SpeakLine(turn = index, partner = (index - 1).takeIf { before != null && before.speaker != role })
        }
    }

    /**
     * The planned lines as recall cards, new on the ladder: what the speaking
     * step shows and what later comes back in review.
     */
    fun cards(dialogue: DialogueModel, lessonId: String, uiLanguage: String): List<RecallCard> =
        plan(dialogue, uiLanguage).map { line ->
            val turn = dialogue.turns[line.turn]
            val partner = line.partner?.let { dialogue.turns[it] }
            RecallCard(
                ref = "${dialogue.id}#t${line.turn}",
                lessonId = lessonId,
                text = turn.text,
                ipa = turn.ipa,
                displayIpa = turn.displayIpa,
                accentIpa = turn.accentIpa,
                audio = turn.audio,
                cues = turn.translations,
                speaker = turn.speaker,
                partnerSpeaker = partner?.speaker.orEmpty(),
                partnerText = partner?.text.orEmpty(),
                partnerAudio = partner?.audio.orEmpty(),
                partnerTranslations = partner?.translations.orEmpty(),
            )
        }

    /** The prompt of a turn in [uiLanguage]: what to say, in the learner's own words. */
    fun cue(turn: DialogueTurn, uiLanguage: String): String? =
        turn.translations[uiLanguage]?.takeIf { it.isNotBlank() }

    private fun DialogueTurn.speakable(): Boolean {
        val words = text.trim().split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }
        return words in MIN_WORDS..MAX_WORDS && ipa.isNotBlank() && audio.isNotEmpty()
    }
}
