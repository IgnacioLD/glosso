package com.glosso.server.generation

import java.util.UUID

/**
 * The Need Engine's stateful half: it reads the learner's stored profile, derives
 * the needs with the pure [NeedEngine], and persists them. Business logic lives
 * here, never in a controller.
 */
class NeedService(
    private val store: ControlPlaneStore,
    private val config: NeedConfig = NeedConfig.DEFAULT,
) {

    /** Recomputes and stores the learner's open needs; returns them. */
    fun deriveAndStore(userId: UUID, language: String): List<UserNeed> {
        val profile = store.learnerProfile(userId, language)
        val phonemes = store.phonemeProfiles(userId, language)
        val needs = NeedEngine.derive(profile, phonemes, config)
        store.replaceOpenNeeds(userId, language, needs)
        return needs
    }

    fun openNeeds(language: String? = null): List<UserNeed> = store.openNeeds(language)
}
