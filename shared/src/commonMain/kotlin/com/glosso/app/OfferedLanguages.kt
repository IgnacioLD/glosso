package com.glosso.app

import com.glosso.engine.model.LanguagePack
import com.glosso.engine.model.LanguagePacks

/**
 * The target languages this build lets the learner pick: what the engine offers
 * ([LanguagePacks.offered]) plus the packs named by the `glossoExtraLanguages`
 * build property (passed to [configure] at startup), which is how a course is tried before its content is published
 * to everyone. A normal build has no extras and offers exactly what the engine says.
 */
object OfferedLanguages {

    /** What the learner can pick; the engine's offer until [configure] adds the build's extras. */
    var all: List<LanguagePack> = offered("")
        private set

    /**
     * Applies the build's extra languages (`glossoExtraLanguages`, comma separated).
     * Called once at startup, before any store resolves a language.
     */
    fun configure(extraCodes: String) {
        all = offered(extraCodes)
    }

    /** Resolves a learner-selected code inside [all]; region forms ("ru-RU") match their base. */
    fun orNull(code: String?): LanguagePack? = resolve(all, code)

    fun offered(extraCodes: String): List<LanguagePack> {
        val extras = extraCodes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { code -> LanguagePacks.all.firstOrNull { it.code == code.lowercase() } }
        return (LanguagePacks.offered + extras).distinctBy { it.code }
    }

    fun resolve(packs: List<LanguagePack>, code: String?): LanguagePack? {
        if (code.isNullOrBlank()) return null
        val normalized = code.trim().lowercase().replace('_', '-')
        return packs.firstOrNull { it.code == normalized }
            ?: packs.firstOrNull { it.code == normalized.substringBefore('-') }
    }
}
