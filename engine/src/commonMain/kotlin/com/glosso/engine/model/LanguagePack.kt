package com.glosso.engine.model

/**
 * Metadata for the shared multilingual phoneme model: a wav2vec2 network whose
 * output labels follow the IPA inventory it was fine-tuned on. The same model
 * serves every language (it was trained on ~60 espeak inventories), so a
 * LanguagePack does not choose a model, only how reference IPA is built.
 */
object PhonemeModelConfig {
    // The selective int8 model: the exact artifact the server loads, so the note
    // is the same in the cloud and on the device (one engine, two sides). The
    // *naive* int8 (all weights) fails on ONNX Runtime CPU with
    // ORT_NOT_IMPLEMENTED - there is no ConvInteger kernel for the 1D
    // convolutions of the feature extractor (docs/FINDINGS.md). This selective
    // variant keeps the convolutions, the feature projection and the lm head in
    // fp32 and quantizes only the encoder's MatMuls, so it loads on ARM64 and
    // x86_64. fp16 is no longer shipped by either side.
    const val MODEL_FILE = "wav2vec2_phoneme_int8_dyn_pertensor.onnx"
    const val VOCAB_FILE = "phoneme_vocab.json"

    /** >= 300 MB sanity floor (the real selective int8 model is ~342 MB). */
    const val MIN_MODEL_BYTES = 1024L * 1024L * 300L
    const val MIN_VOCAB_BYTES = 1024L
}

/**
 * How reference IPA is produced for a language.
 *
 * - [CMU_DICT]: an offline CMUdict-format lexicon (English today).
 * - [LEXICON]: a per-language word -> IPA lexicon published alongside the
 *   content (built with a permissive/publish-time G2P such as espeak-ng).
 * - [PRECOMPUTED]: no runtime G2P; every content item carries its IPA, and the
 *   client must send `expectedIpa`. Used while a language's G2P is not wired.
 */
enum class G2PKind { CMU_DICT, LEXICON, PRECOMPUTED }

/**
 * Everything that makes a language teachable, in one place. Adding a language is
 * data plus one pack: a G2P source, the phoneme inventory, the error rules and
 * the voices. No scoring code changes.
 *
 * [code] is both the content/asset directory key (`content/<code>`) and the code
 * the app sends as the target language; [similarityKey]/[errorRulesKey] select
 * the per-language tables inside the engine.
 */
data class LanguagePack(
    val code: String,
    /** English display name, e.g. "French". */
    val displayName: String,
    /** Endonym shown to the learner, e.g. "Français". */
    val nativeName: String,
    val flag: String,
    /** True while recognition accuracy has not been validated with real audio. */
    val experimental: Boolean,
    /**
     * Whether the model pack the app downloads can score this language. That pack
     * is fine-tuned on English and writes other languages in English phones, so
     * only English is scored on the device; the rest go to the server, which can
     * use the multilingual model for them (`GLOSSO_MODEL_FILES`).
     */
    val onDeviceScoring: Boolean = false,
    val g2pKind: G2PKind,
    /**
     * Asset file holding the lexicon, relative to the language's content dir
     * (`cmudict.dict` for English, `<code>.lexicon` otherwise). Null for
     * [G2PKind.PRECOMPUTED].
     */
    val g2pAsset: String?,
    /** Locale passed to the TTS/G2P pipeline, e.g. "fr-fr". */
    val phonemizerLocale: String,
    /** Symbols the reference IPA is expected to use (docs and validation). */
    val phonemeInventory: Set<String>,
    /** Bucket for [com.glosso.engine.phonetics.PhoneticComparator] similarity. */
    val similarityKey: String,
    /** Bucket for [com.glosso.engine.phonetics.ErrorRules]. */
    val errorRulesKey: String,
    val accents: List<AccentOption>,
    val defaultAccent: String,
    val voices: List<VoiceModel>,
) {
    fun voicesFor(accent: String): List<VoiceModel> = voices.filter { it.accent == accent }

    fun voicesFor(accent: String, gender: VoiceGender): List<VoiceModel> =
        voices.filter { it.accent == accent && it.gender == gender }

    /** The reference voice key ("<accent>-<gender>") when the pair is shipped. */
    fun voiceKeyOrDefault(accent: String, gender: VoiceGender): String {
        val exact = voicesFor(accent, gender).firstOrNull()
            ?: voicesFor(accent).firstOrNull()
            ?: voices.firstOrNull()
            ?: return "$defaultAccent-female"
        return "${exact.accent}-${exact.gender.name.lowercase()}"
    }
}

/**
 * The languages the product ships today. Each pack is self-contained: to add a
 * language, add a pack here and its `content/<code>` bank. See docs/ADD_LANGUAGE.md.
 */
object LanguagePacks {

    val ENGLISH = LanguagePack(
        code = "en",
        displayName = "English",
        nativeName = "English",
        flag = "\uD83C\uDDEC\uD83C\uDDE7",
        experimental = false,
        onDeviceScoring = true,
        g2pKind = G2PKind.CMU_DICT,
        g2pAsset = "cmudict.dict",
        phonemizerLocale = "en-gb",
        phonemeInventory = setOf(
            "iː", "ɪ", "e", "æ", "ɑː", "ɒ", "ɔː", "ʊ", "uː", "ʌ", "ə", "ɜː",
            "eɪ", "aɪ", "ɔɪ", "əʊ", "aʊ", "ɪə", "eə", "ʊə",
            "p", "b", "t", "d", "k", "ɡ", "f", "v", "θ", "ð", "s", "z", "ʃ",
            "ʒ", "h", "tʃ", "dʒ", "m", "n", "ŋ", "l", "ɹ", "j", "w",
        ),
        similarityKey = "en",
        errorRulesKey = "en",
        // British is the variety the course is written in. American has its own
        // voices and its own wording where the British one is audible
        // ([AccentWording]); where an item has no American clip yet, the app
        // plays the British one.
        accents = listOf(
            AccentOption("en-GB", "British"),
            AccentOption("en-US", "American"),
        ),
        defaultAccent = "en-GB",
        voices = listOf(
            VoiceModel("american-female-1", "en", "en-US", VoiceGender.FEMALE, "American female"),
            VoiceModel("american-male-2", "en", "en-US", VoiceGender.MALE, "American male"),
            VoiceModel("bf_lily", "en", "en-GB", VoiceGender.FEMALE, "Lily"),
            VoiceModel("bf_alice", "en", "en-GB", VoiceGender.FEMALE, "Alice"),
            VoiceModel("bm_fable", "en", "en-GB", VoiceGender.MALE, "Fable"),
        ),
    )

    /**
     * French is the reference non-English language. Reference IPA is precomputed
     * at publish time with espeak-ng (the same phonemizer the acoustic model was
     * trained on); the server can also phonemize at runtime from the published
     * lexicon. Reference audio is rendered with Cartesia; the voice ids are the
     * owner's account voices, two per gender.
     *
     * The inventory lists what espeak-ng `fr-fr` writes and the model can emit.
     * The glide of "nuit" is absent on purpose: espeak-ng writes it as /y/
     * (`nyˈi`) and the model has no /ɥ/ token.
     */
    val FRENCH = LanguagePack(
        code = "fr",
        displayName = "French",
        nativeName = "Français",
        flag = "\uD83C\uDDEB\uD83C\uDDF7",
        experimental = true,
        g2pKind = G2PKind.LEXICON,
        g2pAsset = "fr.lexicon",
        phonemizerLocale = "fr-fr",
        phonemeInventory = setOf(
            "i", "y", "u", "e", "ø", "o", "ə", "ɛ", "œ", "ɔ", "a", "ɑ",
            "ɛ̃", "ɑ̃", "ɔ̃", "œ̃",
            "p", "b", "t", "d", "k", "ɡ", "f", "v", "s", "z", "ʃ", "ʒ",
            "m", "n", "ɲ", "ŋ", "l", "ʁ", "j", "w",
        ),
        similarityKey = "fr",
        errorRulesKey = "fr",
        accents = listOf(AccentOption("fr-FR", "French")),
        defaultAccent = "fr-FR",
        voices = listOf(
            VoiceModel("7c58f4a4-a72c-42fa-a503-41b9408820f3", "fr", "fr-FR", VoiceGender.FEMALE, "Inès"),
            VoiceModel("63fdecc2-4e1d-4aa3-a442-27204e3cd3b5", "fr", "fr-FR", VoiceGender.FEMALE, "Léonie"),
            VoiceModel("c6ccfe32-6bee-484a-a8a2-7a51bee93f99", "fr", "fr-FR", VoiceGender.MALE, "Étienne"),
            VoiceModel("93c98a2b-7d15-4f7b-8236-294b1e02b1c0", "fr", "fr-FR", VoiceGender.MALE, "Mathieu"),
        ),
    )

    /**
     * German. Reference IPA comes from espeak-ng `de` at publish time, so the
     * inventory is what that phonemizer writes, checked against the model
     * vocabulary (LanguageInventoryTest). Where the two disagree:
     *
     * - Short Ü: espeak-ng writes /ʏ/, a symbol the model does not have; its
     *   closest token is /y/. The inventory keeps espeak's /ʏ/ (it is what the
     *   lexicon carries), ModelInventory resolves it to /y/ and the German matrix
     *   treats the two as one sound.
     * - R: espeak-ng writes /r/ before a vowel and /ɾ/ elsewhere, never the
     *   uvular /ʁ/ most speakers use. All of r, ɾ, ʁ and ʀ are one phoneme in
     *   the German matrix.
     * - The "-er" ending is /ɜ/ and the vocalised R of "durch" is /ɐ/ (espeak
     *   writes `ʊɐ`; both halves are model tokens).
     * - Long A is /ɑː/, short A is /a/; "eu"/"äu" is /ɔø/.
     *
     * Recognition accuracy with real German speech has not been measured, hence
     * [LanguagePack.experimental].
     */
    val GERMAN = LanguagePack(
        code = "de",
        displayName = "German",
        nativeName = "Deutsch",
        flag = "\uD83C\uDDE9\uD83C\uDDEA",
        experimental = true,
        g2pKind = G2PKind.LEXICON,
        g2pAsset = "de.lexicon",
        phonemizerLocale = "de",
        phonemeInventory = setOf(
            "iː", "ɪ", "eː", "ɛ", "ɛː", "a", "ɑː", "oː", "ɔ", "uː", "ʊ",
            "yː", "ʏ", "øː", "œ", "ə", "ɜ", "ɐ",
            "aɪ", "aʊ", "ɔø",
            "p", "b", "t", "d", "k", "ɡ", "f", "v", "s", "z", "ʃ", "ʒ", "ç", "x", "h",
            "pf", "ts", "tʃ", "dʒ",
            "m", "n", "ŋ", "l", "r", "ɾ", "j",
        ),
        similarityKey = "de",
        errorRulesKey = "de",
        accents = listOf(AccentOption("de-DE", "German")),
        defaultAccent = "de-DE",
        voices = listOf(
            VoiceModel("4ab1ff51-476d-42bb-8019-4d315f7c0c05", "de", "de-DE", VoiceGender.FEMALE, "Lena"),
            VoiceModel("0b66a153-548f-4f2c-b734-09a13b0bd163", "de", "de-DE", VoiceGender.FEMALE, "Lorelei"),
            VoiceModel("57a3a9e0-a91c-4c94-a2bb-e6cbab3ae649", "de", "de-DE", VoiceGender.MALE, "Clemens"),
            VoiceModel("42f14755-88c3-4124-aae3-5cc3a9618e8f", "de", "de-DE", VoiceGender.MALE, "Jan"),
        ),
    )

    /**
     * Russian. Reference IPA comes from espeak-ng `ru` at publish time, with
     * lexical stress marked. The model was trained on the same notation, so
     * every symbol below is both what espeak-ng writes and a model token; they
     * just differ from textbook IPA:
     *
     * - Ы is /y/, not /ɨ/ (so /y/ here is not the French or German Ü).
     * - Hard Л is /ɭ/, soft ЛЬ is /ɭʲ/; soft З is /ʑ/; Щ is /ɕ/; Ш and Ж are
     *   /ʃ/ and /ʒ/.
     * - Vowel reduction is written out: unstressed О/А is /ʌ/ or /a/, unstressed
     *   Е/Я is /i/ or /ɪ/, stressed А is /ɑ/, Ё after a soft consonant is /ɵ/.
     * - Three espeak-ng spellings are rewritten at publish time and again by the
     *   scorer (RU_SYMBOL_FOLDS in PhoneticComparator): Ч `tʃʲ` -> /tʃ/, the
     *   final РЬ `rɪ^` -> /rʲ/ and the fronted У `u"` -> /u/. The inventory
     *   lists the rewritten form.
     *
     * A soft consonant is one model token (/tʲ/) and one phone for content
     * checks, but the scorer compares softness as its own segment /ʲ/.
     * Recognition accuracy with real Russian speech has not been measured, hence
     * [LanguagePack.experimental].
     */
    val RUSSIAN = LanguagePack(
        code = "ru",
        displayName = "Russian",
        nativeName = "Русский",
        flag = "\uD83C\uDDF7\uD83C\uDDFA",
        experimental = true,
        g2pKind = G2PKind.LEXICON,
        g2pAsset = "ru.lexicon",
        phonemizerLocale = "ru",
        phonemeInventory = setOf(
            "a", "ɑ", "ʌ", "e", "ɛ", "i", "ɪ", "o", "ɵ", "u", "y",
            "p", "pʲ", "b", "bʲ", "t", "tʲ", "d", "dʲ", "k", "kʲ", "ɡ", "ɡʲ",
            "f", "fʲ", "v", "vʲ", "s", "sʲ", "z", "ʑ", "ʃ", "ʒ", "ɕ", "x",
            "ts", "tʃ",
            "m", "mʲ", "n", "nʲ", "ɭ", "ɭʲ", "r", "rʲ", "j",
        ),
        similarityKey = "ru",
        errorRulesKey = "ru",
        accents = listOf(AccentOption("ru-RU", "Russian")),
        defaultAccent = "ru-RU",
        voices = listOf(
            VoiceModel("064b17af-d36b-4bfb-b003-be07dba1b649", "ru", "ru-RU", VoiceGender.FEMALE, "Tatiana"),
            VoiceModel("642014de-c0e3-4133-adc0-36b5309c23e6", "ru", "ru-RU", VoiceGender.FEMALE, "Irina"),
            VoiceModel("1e4176b1-3db9-44d6-a601-4fe68b041942", "ru", "ru-RU", VoiceGender.MALE, "Sergei"),
            VoiceModel("888b7df4-e165-4852-bfec-0ab2b96aaa46", "ru", "ru-RU", VoiceGender.MALE, "Dmitri"),
        ),
    )

    /**
     * Every wired pack, English first. French, German and Russian stay here
     * (with their G2P, rules and similarity matrix) so resolution, publishing,
     * scoring and the multi-language tests work; each is [offered] only once its
     * `content/<code>` bank ships.
     */
    val all: List<LanguagePack> = listOf(ENGLISH, FRENCH, GERMAN, RUSSIAN)

    val default: LanguagePack get() = ENGLISH

    /**
     * The packs the product offers learners today. A language is offered only when
     * its `content/<code>` bank ships, so the app can never pick one half-built.
     * French and German are deliberately absent until theirs do. See
     * docs/ADD_LANGUAGE.md.
     */
    val offered: List<LanguagePack> = listOf(ENGLISH, RUSSIAN)

    /**
     * Resolves a client language code to a known pack. Accepts region forms
     * ("en_GB", "fr-FR") by matching the base subtag, and returns null when
     * unsupported. Use [offeredOrNull] to gate a learner-facing selection.
     */
    fun byCodeOrNull(code: String?): LanguagePack? {
        if (code.isNullOrBlank()) return default
        return resolve(all, code)
    }

    /**
     * Resolves a learner-selected code inside [offered]; null when the language is
     * known but not on offer (e.g. French today) or entirely unsupported.
     */
    fun offeredOrNull(code: String?): LanguagePack? {
        if (code.isNullOrBlank()) return null
        return resolve(offered, code)
    }

    private fun resolve(packs: List<LanguagePack>, code: String): LanguagePack? {
        val normalized = code.trim().lowercase().replace('_', '-')
        val base = normalized.substringBefore('-')
        return packs.firstOrNull { it.code == normalized }
            ?: packs.firstOrNull { it.code == base }
    }

    fun byCode(code: String?): LanguagePack = byCodeOrNull(code) ?: default

    /** The nearest shipped pack for scoring/feedback; unsupported falls to default. */
    fun forScoring(code: String?): LanguagePack = byCodeOrNull(code) ?: default
}
