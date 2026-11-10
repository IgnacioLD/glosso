package com.glosso.engine.phonetics

import com.glosso.engine.model.FluencyModel
import com.glosso.engine.model.LetterFeedbackModel
import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PairHint
import com.glosso.engine.model.PhoneAcousticsModel
import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.PronunciationQualityModel
import com.glosso.engine.model.ProsodyBonusModel
import com.glosso.engine.model.StressReportModel
import com.glosso.engine.model.WordFeedbackModel

/**
 * Turns a raw [ScoringResult] into the learner-facing [PronunciationFeedback]
 * shared by every client (Android app and cloud service).
 *
 * Kept in the engine so the cloud and on-device paths produce identical feedback,
 * not just identical scores. Localized fallback strings are intentionally absent:
 * only curated minimal-pair descriptions are attached here, and the UI formats
 * anything else, so the engine stays free of platform resources.
 */
object FeedbackAssembler {

    private const val MAX_HINTS = 3

    fun assemble(
        text: String,
        expectedIpa: String,
        result: ScoringResult,
        targetLanguage: String = "en_GB",
        uiLanguage: String = "en",
        timings: List<PhoneTimingModel> = emptyList(),
        profile: PhoneticProfileModel? = null,
        stress: StressReportModel? = null,
        fluency: FluencyModel? = null,
        acoustics: List<PhoneAcousticsModel> = emptyList(),
        quality: PronunciationQualityModel? = null,
        prosody: ProsodyBonusModel? = null,
    ): PronunciationFeedback {
        val letters = PhoneticComparator.generateLetterFeedback(
            text = text,
            expectedIpa = expectedIpa,
            phonemeAlignment = result.alignment,
            language = targetLanguage,
        )

        // Pair each word score with the sentence word at the same position, so
        // the learner can be told which word to work on. The character feedback
        // is sliced the same way, so the UI can mark the exact letters that did
        // not land inside each word.
        // A token with no letter or digit (a spaced dash) is punctuation: the
        // reference has no word for it, so it must not shift the pairing.
        val wordSpans = Regex("\\S+").findAll(text).filter { span -> span.value.any { it.isLetterOrDigit() } }.toList()
        val textWords = wordSpans.map { it.value }
        val words = result.words.map { wordScore ->
            val index = wordScore.index
            val wordLetters = letters
                .sliceBySpan(wordSpans.getOrNull(index)?.range)
                .map { info -> LetterFeedbackModel(char = info.char, status = info.status.toModel()) }
            WordFeedbackModel(
                text = textWords.getOrNull(index)?.trim().orEmpty(),
                score = wordScore.score,
                level = wordScore.level,
                letters = wordLetters,
            )
        }

        val pairHints = result.alignment
            .filter { it.expected != it.actual && it.expected.isNotBlank() && it.status != MatchStatus.PERFECT }
            .mapNotNull { match ->
                val description = PhoneticComparator.getMinimalPairDescription(
                    expected = match.expected,
                    actual = match.actual,
                    uiLanguage = uiLanguage,
                    targetLanguage = targetLanguage,
                ) ?: return@mapNotNull null
                PairHint(match.expected, match.actual, description)
            }
            .distinctBy { setOf(it.expected, it.actual) }
            .take(MAX_HINTS)

        return PronunciationFeedback(
            score = result.score,
            transcription = result.normalizedActual,
            normalizedExpected = result.normalizedExpected,
            normalizedActual = result.normalizedActual,
            accuracy = result.accuracy,
            completeness = result.completeness,
            words = words,
            timings = timings,
            errors = ErrorRules.analyze(result.alignment, targetLanguage),
            stress = stress,
            profile = profile,
            isMastery = result.isMastery,
            level = result.level,
            fluency = fluency,
            acoustics = acoustics,
            quality = quality,
            prosody = prosody,
            alignment = result.alignment.map { match ->
                PhonemeMatchModel(
                    expected = match.expected,
                    actual = match.actual,
                    status = match.status.toModel(),
                )
            },
            letterFeedback = letters.map { info ->
                LetterFeedbackModel(char = info.char, status = info.status.toModel())
            },
            pairHints = pairHints,
        )
    }

    private fun MatchStatus.toModel(): MatchStatusModel = when (this) {
        MatchStatus.PERFECT -> MatchStatusModel.PERFECT
        MatchStatus.CLOSE -> MatchStatusModel.CLOSE
        MatchStatus.MISSED -> MatchStatusModel.MISSED
    }
}

/** The characters of one word from the whole-text character feedback, or none. */
private fun List<LetterFeedbackInfo>.sliceBySpan(range: IntRange?): List<LetterFeedbackInfo> =
    if (range == null || range.last >= size) emptyList()
    else subList(range.first, range.last + 1)
