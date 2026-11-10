package com.glosso.engine.drills

import com.glosso.engine.model.DrillBank
import kotlinx.serialization.json.Json

/** Loads and merges published drill banks from their JSON form. */
object DrillBankLoader {

    val json: Json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): DrillBank = json.decodeFromString(text)

    /** Merges banks (e.g. one file per theme) into a single bank, keeping versions. */
    fun merge(banks: List<DrillBank>): DrillBank {
        if (banks.size == 1) return banks.single()
        val first = banks.firstOrNull() ?: return DrillBank()
        return DrillBank(
            version = banks.maxOf { it.version },
            language = first.language,
            drills = banks.flatMap { it.drills },
        )
    }
}
