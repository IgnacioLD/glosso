package com.glosso.engine.phonetics.quality

/**
 * Maps reference phones onto the acoustic model's vocabulary the same way
 * `benchmark/phon-multilingual/common.py:token_candidates` does. The phonemizer
 * and the model do not always spell a sound the same way (rhotic schwa, g vs ɡ,
 * length marks), so each phone falls back to the closest token the vocabulary
 * actually has.
 *
 * Shared by the recognizer's forced alignment, the quality head and the parity
 * test, so the benchmark and the port resolve identically. If this diverges the
 * quality head silently reads different tokens than it was trained on.
 */
object ModelInventory {

    /** Alternate vocabulary spellings of [phone], most preferred first. */
    fun candidates(phone: String): List<String> = buildList {
        add(phone)
        if (phone.endsWith("ː")) add(phone.removeSuffix("ː"))
        when (phone) {
            "g" -> add("ɡ")
            "ɹ" -> add("r")
            "ɝ" -> { add("ɜː"); add("ɜ") }
            "ɐ" -> add("ə")
            // German short Ü: espeak-ng writes ʏ, the vocabulary only has y.
            // No English reference contains it, so the benchmark is unaffected.
            "ʏ" -> add("y")
        }
    }

    /** The vocabulary spelling of [phone], or null when the model has none. */
    fun resolve(phone: String, tokenIds: Map<String, Int>): String? =
        candidates(phone).firstOrNull { it in tokenIds }

    /** Resolves a whole reference, or null when any phone is unmappable. */
    fun resolveAll(phones: List<String>, tokenIds: Map<String, Int>): List<String>? {
        val resolved = ArrayList<String>(phones.size)
        for (phone in phones) {
            resolved.add(resolve(phone, tokenIds) ?: return null)
        }
        return resolved
    }
}
