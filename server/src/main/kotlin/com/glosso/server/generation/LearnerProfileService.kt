package com.glosso.server.generation

import java.time.Instant
import java.util.UUID

/** One sound's aggregate as sent by the client. No audio, no free text. */
data class PhonemeSnapshot(
    val phoneme: String,
    val score: Int = 0,
    val confidence: Float = 0f,
    val errorPattern: String? = null,
    val replacementPhoneme: String? = null,
    val position: String? = null,
    val attempts: Int = 0,
    val successfulAttempts: Int = 0,
    val lastSeen: Instant? = null,
    val trend: String? = null,
) {
    fun toProfile(userId: UUID, language: String): PhonemeProfile = PhonemeProfile(
        userId = userId,
        language = language,
        phoneme = phoneme,
        score = score,
        confidence = confidence,
        errorPattern = errorPattern,
        replacementPhoneme = replacementPhoneme,
        position = position,
        attempts = attempts,
        successfulAttempts = successfulAttempts,
        lastSeen = lastSeen,
        trend = trend,
    )
}

/** The validated, decoded payload of one profile sync. */
data class ProfileSyncInput(
    val deviceId: String,
    val language: String,
    val cefr: String? = null,
    val vocabularyScore: Int? = null,
    val grammarScore: Int? = null,
    val comprehensionScore: Int? = null,
    val pronunciationScore: Int? = null,
    val fluencyScore: Int? = null,
    val speakingScore: Int? = null,
    val sampleCountsJson: String = "{}",
    val phonemes: List<PhonemeSnapshot> = emptyList(),
)

data class ProfileSyncResult(
    val userId: UUID,
    val profile: LearnerProfile,
    val phonemes: List<PhonemeProfile>,
)

/**
 * Owns the learner's profile: the anonymous user anchor, the per-skill scores and
 * the per-sound aggregate. It is the only place the server learns anything about
 * a learner, and what it learns is statistics, never audio or identity.
 */
class LearnerProfileService(private val store: ControlPlaneStore) {

    fun sync(input: ProfileSyncInput): ProfileSyncResult {
        val userId = store.upsertUser(input.deviceId)
        val profile = LearnerProfile(
            userId = userId,
            language = input.language,
            cefr = input.cefr,
            vocabularyScore = input.vocabularyScore,
            grammarScore = input.grammarScore,
            comprehensionScore = input.comprehensionScore,
            pronunciationScore = input.pronunciationScore,
            fluencyScore = input.fluencyScore,
            speakingScore = input.speakingScore,
            sampleCountsJson = input.sampleCountsJson,
        )
        store.upsertLearnerProfile(profile)
        val phonemes = input.phonemes.map { it.toProfile(userId, input.language) }
        store.replacePhonemeProfiles(userId, input.language, phonemes)
        return ProfileSyncResult(userId, profile, phonemes)
    }

    fun profile(userId: UUID, language: String): LearnerProfile? = store.learnerProfile(userId, language)
}
