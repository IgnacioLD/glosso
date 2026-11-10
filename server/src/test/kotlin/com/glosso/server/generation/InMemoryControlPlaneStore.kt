package com.glosso.server.generation

import java.util.UUID

/**
 * Test double for [ControlPlaneStore]. It mirrors the JDBC semantics that matter:
 * a device id maps to one user, a sync replaces the per-sound aggregate and the
 * open needs, and need classes are keyed by their anonymous identity.
 */
class InMemoryControlPlaneStore : ControlPlaneStore {

    private val userIds = LinkedHashMap<String, UUID>()
    private val profiles = HashMap<Pair<UUID, String>, LearnerProfile>()
    private val phonemes = HashMap<Pair<UUID, String>, List<PhonemeProfile>>()
    private val openNeeds = HashMap<Pair<UUID, String>, List<UserNeed>>()
    private val needClasses = LinkedHashMap<String, NeedClass>()
    private val needClassIds = HashMap<String, Long>()
    private var nextNeedClassId = 1L

    override fun upsertUser(deviceId: String): UUID = userIds.getOrPut(deviceId) { UUID.randomUUID() }

    override fun upsertLearnerProfile(profile: LearnerProfile) {
        profiles[profile.userId to profile.language] = profile
    }

    override fun learnerProfile(userId: UUID, language: String): LearnerProfile? =
        profiles[userId to language]

    override fun replacePhonemeProfiles(userId: UUID, language: String, profiles: List<PhonemeProfile>) {
        phonemes[userId to language] = profiles
    }

    override fun phonemeProfiles(userId: UUID, language: String): List<PhonemeProfile> =
        phonemes[userId to language].orEmpty()

    override fun replaceOpenNeeds(userId: UUID, language: String, needs: List<UserNeed>) {
        openNeeds[userId to language] = needs
    }

    override fun openNeeds(language: String?): List<UserNeed> =
        openNeeds.values.flatten().filter { language == null || it.language == language }

    override fun upsertNeedClasses(classes: List<NeedClass>) {
        for (needClass in classes) {
            val key = keyOf(needClass.key)
            needClasses[key] = needClass
            needClassIds.getOrPut(key) { nextNeedClassId++ }
        }
    }

    override fun needClassId(key: NeedClassKey): Long? = needClassIds[keyOf(key)]

    override fun needClasses(language: String?, limit: Int): List<NeedClass> =
        needClasses.values
            .filter { language == null || it.key.language == language }
            .sortedWith(
                compareByDescending<NeedClass> { it.priority }
                    .thenByDescending { it.learnerCount }
                    .thenBy { it.key.cefr },
            )
            .take(limit.coerceIn(1, 1000))

    private fun keyOf(key: NeedClassKey): String = listOf(
        key.language,
        key.cefr,
        key.skill,
        key.phoneme.orEmpty(),
        key.errorPattern.orEmpty(),
        key.replacementPhoneme.orEmpty(),
        key.position.orEmpty(),
    ).joinToString("|")
}
