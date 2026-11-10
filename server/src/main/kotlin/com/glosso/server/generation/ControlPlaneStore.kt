package com.glosso.server.generation

import java.util.UUID

/**
 * Persistence for the generation control plane. Services depend on this, not on
 * JDBC, so the derivation and aggregation logic stay testable without a database.
 */
interface ControlPlaneStore : AutoCloseable {

    /** Resolves the anonymous per-install id to a user row, creating it if new. */
    fun upsertUser(deviceId: String): UUID

    fun upsertLearnerProfile(profile: LearnerProfile)

    fun learnerProfile(userId: UUID, language: String): LearnerProfile?

    /** Replaces the learner's per-sound aggregate for one language. */
    fun replacePhonemeProfiles(userId: UUID, language: String, profiles: List<PhonemeProfile>)

    fun phonemeProfiles(userId: UUID, language: String): List<PhonemeProfile>

    /**
     * Replaces the learner's open needs for one language. Already addressed or
     * resolved needs are kept, so a need that stopped firing is not recreated.
     */
    fun replaceOpenNeeds(userId: UUID, language: String, needs: List<UserNeed>)

    /** Open needs across learners, optionally scoped to a language. */
    fun openNeeds(language: String? = null): List<UserNeed>

    fun upsertNeedClasses(classes: List<NeedClass>)

    fun needClasses(language: String? = null, limit: Int = 100): List<NeedClass>

    /** The stored Need Class id for a key, or null when it was never aggregated. */
    fun needClassId(key: NeedClassKey): Long?

    override fun close() {}
}
