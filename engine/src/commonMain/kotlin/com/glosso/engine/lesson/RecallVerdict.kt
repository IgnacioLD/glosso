package com.glosso.engine.lesson

import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PronunciationFeedback

/**
 * Whether a take said from memory was the phrase that was asked for. A learner
 * who says something else has a recall problem, not a pronunciation one: that
 * take must not be shown as a pronunciation score nor folded into the sound
 * profile, where every expected phone would count as a miss.
 */
enum class RecallVerdict {
    /** The phrase, close enough to judge its pronunciation. */
    RECALLED,

    /** Part of the phrase: some of it was missing or different. */
    PARTIAL,

    /** Another phrase, or too little of this one to judge. */
    NOT_THAT_PHRASE;

    /** Only a recalled or partly recalled phrase says something about the learner's sounds. */
    val judgesPronunciation: Boolean get() = this != NOT_THAT_PHRASE

    companion object {
        const val RECALLED_AT = 0.75
        const val PARTIAL_AT = 0.45

        /**
         * From the alignment of the take against the phrase: the share of the
         * expected phones that were heard, exactly or as a close sound. The
         * thresholds are a first setting, not calibrated on learner takes.
         */
        fun of(feedback: PronunciationFeedback): RecallVerdict {
            val expected = feedback.alignment.filter { it.expected.isNotBlank() }
            if (expected.isEmpty()) return NOT_THAT_PHRASE
            val heard = expected.count { it.status != MatchStatusModel.MISSED }.toDouble() / expected.size
            return when {
                heard >= RECALLED_AT -> RECALLED
                heard >= PARTIAL_AT -> PARTIAL
                else -> NOT_THAT_PHRASE
            }
        }
    }
}
