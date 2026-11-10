package com.glosso.engine.accent

import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.model.VoiceGender

/**
 * The English accent systems and their reference voices.
 *
 * The voice UUIDs are the owner's provider voices, whose API is not wired yet
 * (docs/MULTI-ACCENT.md, "Subir una voz"). The first set came from
 * design-reference/voces-acentos.txt; the voices the owner selected later
 * (midwestern-american, canadian-english, new-zealand and the extra genders per
 * accent) are registered here so they join a system. en-GB also keeps the Kokoro
 * voices the product already ships.
 *
 * Every phonology is a seed from standard descriptions of the accent and is
 * `calibrated = false` until its native audio is measured. Rules only touch
 * sounds the acoustic model's vocabulary can emit (aː, eː, oː, ʊə, ʉ are in
 * `phoneme_vocab.json`), so a native of the accent can actually match them.
 */
object AccentCatalog {

    private const val NOT_BEFORE_GLIDE_OR_R = "(?![ɪɹ])"

    /** cot/caught merger: THOUGHT (ɔ) is said with the LOT vowel (ɑ). */
    val COT_CAUGHT = AccentRule(
        id = "cot-caught",
        description = "THOUGHT merges into LOT: caught = cot",
        pattern = Regex("ɔ$NOT_BEFORE_GLIDE_OR_R"),
        replacement = "ɑ",
    )

    /** Raised THOUGHT of New York: "talk", "dog" with a centring glide. */
    val THOUGHT_RAISING = AccentRule(
        id = "thought-raising",
        description = "THOUGHT raised to a centring diphthong (ʊə)",
        pattern = Regex("ɔ$NOT_BEFORE_GLIDE_OR_R"),
        replacement = "ʊə",
    )

    /** Southern PRICE: the glide is lost, "time" is a long open vowel. */
    val PRICE_MONOPHTHONG = AccentRule(
        id = "price-monophthong",
        description = "PRICE loses its glide: aɪ -> aː",
        pattern = Regex("aɪ"),
        replacement = "aː",
    )

    /** pin/pen merger: DRESS before a nasal is said with KIT. */
    val PIN_PEN = AccentRule(
        id = "pin-pen",
        description = "DRESS before a nasal merges into KIT: pen = pin",
        pattern = Regex("ɛ(?=[nm])"),
        replacement = "ɪ",
    )

    /** Irish TH: dental stops instead of fricatives (the model folds t̪ into t). */
    val TH_STOPPING = AccentRule(
        id = "th-stopping",
        description = "voiceless TH as a dental stop: θ -> t",
        pattern = Regex("θ"),
        replacement = "t",
    )

    val DH_STOPPING = AccentRule(
        id = "dh-stopping",
        description = "voiced TH as a dental stop: ð -> d",
        pattern = Regex("ð"),
        replacement = "d",
    )

    /** Irish FACE and GOAT are long monophthongs. */
    val FACE_MONOPHTHONG = AccentRule("face-monophthong", "FACE as a long monophthong: eɪ -> eː", Regex("eɪ"), "eː")
    val GOAT_MONOPHTHONG = AccentRule("goat-monophthong", "GOAT as a long monophthong: oʊ -> oː", Regex("oʊ"), "oː")

    /** Australian diphthong shift. */
    val FACE_WIDE = AccentRule("face-wide", "FACE with an open onset: eɪ -> æɪ", Regex("eɪ"), "æɪ")
    val PRICE_BACK = AccentRule("price-back", "PRICE with a back onset: aɪ -> ɑɪ", Regex("aɪ"), "ɑɪ")
    val GOAT_FRONT = AccentRule("goat-front", "GOAT with a central onset and fronted glide: oʊ -> əʉ", Regex("oʊ"), "əʉ")

    /** Midwestern bag/rag raising: TRAP before a velar is a raised centring vowel. */
    val BAG_RAISING = AccentRule(
        id = "bag-raising",
        description = "TRAP before a velar raises: bag / flag with a centring glide (æ -> eə)",
        pattern = Regex("æ(?=ɡ)"),
        replacement = "eə",
    )

    /**
     * Canadian raising: the PRICE and MOUTH onsets raise before a voiceless
     * consonant ("right", "about"), and stay low before a voiced one ("ride",
     * "loud").
     */
    val CANADIAN_PRICE = AccentRule(
        id = "canadian-raising-price",
        description = "PRICE raised before a voiceless consonant: aɪ -> əɪ",
        pattern = Regex("aɪ(?=[ptkfs])"),
        replacement = "əɪ",
    )
    val CANADIAN_MOUTH = AccentRule(
        id = "canadian-raising-mouth",
        description = "MOUTH raised before a voiceless consonant: aʊ -> ʌʊ",
        pattern = Regex("aʊ(?=[ptkfs])"),
        replacement = "ʌʊ",
    )

    /** New Zealand DRESS raising, part of its short front vowel shift. */
    val NZ_DRESS_RAISING = AccentRule(
        id = "nz-dress-raising",
        description = "DRESS raised toward KIT: ɛ -> ɪ",
        pattern = Regex("ɛ"),
        replacement = "ɪ",
    )

    val EN_GB = AccentPhonology("en-GB", rhotic = false)
    val EN_US = AccentPhonology("en-US", rhotic = true)
    val EN_US_CA = AccentPhonology("en-US-CA", rhotic = true, rules = listOf(COT_CAUGHT))
    val EN_US_SOUTH = AccentPhonology("en-US-South", rhotic = true, rules = listOf(PRICE_MONOPHTHONG, PIN_PEN))
    val EN_US_NY = AccentPhonology("en-US-NY", rhotic = false, rules = listOf(THOUGHT_RAISING))
    val EN_IE = AccentPhonology(
        "en-IE",
        rhotic = true,
        rules = listOf(TH_STOPPING, DH_STOPPING, FACE_MONOPHTHONG, GOAT_MONOPHTHONG),
    )
    val EN_AU = AccentPhonology("en-AU", rhotic = false, rules = listOf(FACE_WIDE, PRICE_BACK, GOAT_FRONT))
    val EN_US_MIDWEST = AccentPhonology(
        "midwestern-american",
        rhotic = true,
        rules = listOf(COT_CAUGHT, BAG_RAISING),
    )
    val EN_CA = AccentPhonology(
        "canadian-english",
        rhotic = true,
        rules = listOf(CANADIAN_PRICE, CANADIAN_MOUTH),
    )
    val EN_NZ = AccentPhonology("new-zealand", rhotic = false, rules = listOf(NZ_DRESS_RAISING))

    private data class AccentEntry(val phonology: AccentPhonology, val label: String)

    /** Catalogue order is the last tie-break between systems that score the same. */
    private val accents = listOf(
        AccentEntry(EN_GB, "British"),
        AccentEntry(EN_US, "American"),
        AccentEntry(EN_US_CA, "Californian"),
        AccentEntry(EN_US_SOUTH, "Southern US"),
        AccentEntry(EN_US_NY, "New York"),
        AccentEntry(EN_IE, "Irish"),
        AccentEntry(EN_AU, "Australian"),
        AccentEntry(EN_US_MIDWEST, "Midwestern"),
        AccentEntry(EN_CA, "Canadian"),
        AccentEntry(EN_NZ, "New Zealand"),
    )

    private fun f(id: String, accent: String, label: String) = ReferenceVoice(id, accent, VoiceGender.FEMALE, label)
    private fun m(id: String, accent: String, label: String) = ReferenceVoice(id, accent, VoiceGender.MALE, label)

    /**
     * The owner's provider voices (design-reference/voces-acentos.txt plus the
     * accent selection that followed it). "british" in that selection is this
     * catalogue's en-GB, so its voices join the shipped en-GB (Kokoro) ones. The
     * voices the owner selected before their accent had a phonology
     * (midwestern-american, canadian-english, new-zealand) are here so they join a
     * system once registered.
     */
    val PROVIDER_VOICES: List<ReferenceVoice> = listOf(
        f("391f4c0a-f1a8-4c21-9aa2-7a07f0a4b0dc", "en-AU", "AU female 1"),
        f("2a12b36c-7f9b-4c3a-9f7a-72731b15323a", "en-AU", "AU female 2"),
        m("12e85709-099c-480a-ba3e-875c41a9611a", "en-AU", "AU male 1"),
        m("49743b08-0f5d-4741-839c-b12933853780", "en-AU", "AU male 2"),
        m("79d2cf27-444a-4c3a-9eed-2ad5cf795a3b", "en-AU", "AU male 3"),
        f("21b81c14-f85b-436d-aff5-43f2e788ecf8", "en-US-CA", "California female 1"),
        f("b7d50908-b17c-442d-ad8d-810c63997ed9", "en-US-CA", "California female 2"),
        f("d1d9c946-7cfc-4378-85a4-07d09827cb7e", "en-US-South", "Southern US female 1"),
        f("78ab82d5-25be-4f7d-82b3-7ad64e5b85b2", "en-US-South", "Southern US female 2"),
        m("5fc5c797-12c5-4f2b-ac9b-d4e53c08098f", "en-US-South", "Southern US male 1"),
        m("3d83e30f-c31b-4f26-b442-7075feafa53a", "en-US-South", "Southern US male 2"),
        m("8e14933d-ecd7-402b-9505-795130d69b35", "en-US-NY", "New York male 1"),
        m("34575e71-908f-4ab6-ab54-b08c95d6597d", "en-US-NY", "New York male 2"),
        m("79b8126f-c5d9-4a73-8585-ba5e1a077ed6", "en-US-NY", "New York male 3"),
        m("1ec736fa-db96-4eea-9299-235ce2cb7a0e", "en-IE", "Irish male 1"),
        m("d3e3d5d5-07b0-484f-9967-dbc8f15b60d5", "en-IE", "Irish male 2"),
        f("d79d2b77-9192-4e10-9407-5d43ca034803", "en-IE", "Irish female 1"),
        f("db6b0ed5-d5d3-463d-ae85-518a07d3c2b4", "en-US", "American female 1"),
        f("9626c31c-bec5-4cca-baa8-f8ba9e84c8bc", "en-US", "American female 2"),
        m("630ed21c-2c5c-41cf-9d82-10a7fd668370", "en-US", "American male 1"),
        m("47c38ca4-5f35-497b-b1a3-415245fb35e1", "en-US", "American male 2"),
        m("30894953-bcce-41fe-892c-15ce19c843ff", "en-US", "American male 3"),
        f("62ae83ad-4f6a-430b-af41-a9bede9286ca", "en-GB", "British female 1"),
        f("c46cf1f6-49a1-4d67-9a57-ff859a4046d3", "en-GB", "British female 2"),
        m("ef191366-f52f-447a-a398-ed8c0f2943a1", "en-GB", "British male 1"),
        m("b24f41fd-00a3-4cd8-992a-a0c9f13f3ef1", "en-GB", "British male 2"),
        f("11af83e2-23eb-452f-956e-7fee218ccb5c", "midwestern-american", "Midwestern female 1"),
        m("3e39e9a5-585c-4f5f-bac6-5e4905c51095", "midwestern-american", "Midwestern male 1"),
        m("565510e8-6b45-45de-8758-13588fbaec73", "midwestern-american", "Midwestern male 2"),
        f("996a8b96-4804-46f0-8e05-3fd4ef1a87cd", "canadian-english", "Canadian female 1"),
        m("3945925b-9143-4b7d-9ab5-cc4878001106", "canadian-english", "Canadian male 1"),
        f("24894159-1d4e-4b7c-80ca-4ae37dce9400", "new-zealand", "New Zealand female 1"),
        f("db408a93-859c-4a0a-b6a2-220c074cc90d", "new-zealand", "New Zealand female 2"),
    )

    /** The en-GB voices the product already ships (Kokoro), from the language pack. */
    private val shippedVoices: List<ReferenceVoice> = LanguagePacks.ENGLISH.voices.map {
        ReferenceVoice(it.id, it.accent, it.gender, it.name)
    }

    val voices: List<ReferenceVoice> = shippedVoices + PROVIDER_VOICES

    /**
     * One system per (accent, gender) pair that has at least one reference voice,
     * in catalogue order, female first. A pair without a voice has no reference
     * and is not a system.
     */
    val ENGLISH: List<AccentSystem> = accents.flatMap { entry ->
        VoiceGender.entries.mapNotNull { gender ->
            val own = voices.filter { it.accent == entry.phonology.accent && it.gender == gender }
            if (own.isEmpty()) null else AccentSystem(entry.phonology.accent, entry.label, gender, entry.phonology, own)
        }
    }

    val accentIds: List<String> = accents.map { it.phonology.accent }

    fun phonology(accent: String): AccentPhonology? =
        accents.firstOrNull { it.phonology.accent.equals(accent, ignoreCase = true) }?.phonology

    fun system(id: String): AccentSystem? = ENGLISH.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun voice(voiceId: String): ReferenceVoice? = voices.firstOrNull { it.voiceId.equals(voiceId, ignoreCase = true) }

    /** The systems for a language; only English has more than one accent today. */
    fun systemsFor(language: String): List<AccentSystem> =
        if (language.lowercase().startsWith("en")) ENGLISH else emptyList()

    /**
     * The systems a take is scored against: the accents the language pack offers
     * to the learner, not the whole catalogue. The other systems are seeds whose
     * rules were never confirmed with audio (docs/MULTI-ACCENT.md); they stay in
     * the catalogue for the voice registry and do not decide a score.
     */
    fun scoringSystemsFor(language: String): List<AccentSystem> {
        val offered = LanguagePacks.forScoring(language).accents.map { it.code.lowercase() }.toSet()
        return systemsFor(language).filter { it.accent.lowercase() in offered }
    }
}
