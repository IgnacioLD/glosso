package com.glosso.engine.phonetics

import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.model.PhonemeErrorKindModel
import com.glosso.engine.model.PhonemeErrorModel

/**
 * Turns a phone alignment into named, actionable errors instead of a bare
 * "missed" mark. A rule only fires for a contrast the language actually has and
 * that we can teach ("TH said as S"), so the feedback stays trustworthy: an
 * unfamiliar substitution is reported as a plain drop or substitution, never
 * with a made-up explanation.
 *
 * Rules are per language, selected through the [LanguagePacks] registry, so the
 * same (reference, produced) pair can be taught differently in English and
 * French. English keys use the symbols CMUdict emits; French, German and Russian
 * keys use the ones espeak-ng emits. Lookups ignore vowel length so the same rule
 * also matches a phonemizer that writes /iː/ instead of /i/.
 *
 * Unknown languages return an empty list: never invent a rule for a language we
 * have not curated.
 */
object ErrorRules {

    /** At most this many distinct errors per attempt, so feedback stays focused. */
    const val MAX_ERRORS = 5

    /** A curated error rule, exposed for the drill bank and the UI. */
    data class RuleInfo(val id: String, val title: String, val examples: List<String>)

    private data class Rule(val id: String, val title: String, val examples: List<String>)

    private data class Sub(
        val target: String,
        val actual: String,
        val id: String,
        val title: String,
        val pair: List<String>,
    )

    /**
     * Keyed by (reference, produced), so the direction matters: "TH said as S" is
     * a different lesson from "S said as TH".
     */
    private val enSubstitutions: List<Sub> = listOf(
        // TH, the single most common Spanish-speaker error.
        Sub("θ", "s", "en.th.stopping", "TH (think) is coming out as S. Put your tongue tip between your teeth.", listOf("think", "sink")),
        Sub("θ", "t", "en.th.stopping.t", "TH (thin) is coming out as T. Keep the airflow, don't stop it.", listOf("thin", "tin")),
        Sub("θ", "f", "en.th.fronting", "TH (three) is coming out as F. Let your tongue touch your teeth.", listOf("three", "free")),
        Sub("ð", "d", "en.dh.stopping", "Voiced TH (they) is coming out as D. Tongue between the teeth, keep buzzing.", listOf("they", "day")),
        Sub("ð", "z", "en.dh.z", "Voiced TH (breathe) is coming out as Z. Tongue tip between the teeth.", listOf("breathe", "breeze")),
        Sub("s", "θ", "en.s.th", "S (sink) is coming out as TH. Keep your tongue behind your teeth.", listOf("sink", "think")),
        // S/Z voicing.
        Sub("z", "s", "en.z.s", "Z (eyes) is coming out as S. Add voice; keep buzzing at the end.", listOf("eyes", "ice")),
        Sub("s", "z", "en.s.z", "S (ice) is coming out as Z. Don't let your throat buzz.", listOf("ice", "eyes")),
        // SH / CH / J.
        Sub("ʃ", "tʃ", "en.sh.ch", "SH (sheep) is coming out as CH. Don't start it with a T.", listOf("sheep", "cheap")),
        Sub("tʃ", "ʃ", "en.ch.sh", "CH (cheap) is losing its T. Start with a quick stop.", listOf("cheap", "sheep")),
        Sub("dʒ", "tʃ", "en.j.ch", "J (jeer) is coming out as CH. Add your voice to the buzz.", listOf("jeer", "cheer")),
        Sub("tʃ", "dʒ", "en.ch.j", "CH (cheer) is coming out as J. Keep it voiceless.", listOf("cheer", "jeer")),
        // R / L / W.
        Sub("ɹ", "w", "en.r.w", "R (right) is coming out as W. Curl or bunch your tongue; don't round your lips.", listOf("right", "white")),
        Sub("ɹ", "l", "en.r.l", "R (right) is coming out as L. Your tongue tip must not touch the roof.", listOf("right", "light")),
        Sub("l", "ɹ", "en.l.r", "L (light) is coming out as R. Touch the roof of your mouth with your tongue tip.", listOf("light", "right")),
        Sub("w", "v", "en.w.v", "W (west) is coming out as V. Round your lips; don't touch the lower lip to the teeth.", listOf("west", "vest")),
        Sub("v", "w", "en.v.w", "V (vest) is coming out as W. Touch your lower lip to your upper teeth.", listOf("vest", "west")),
        // B/V merge.
        Sub("b", "v", "en.b.v", "B (best) is coming out as V. Press both lips together, then release.", listOf("best", "vest")),
        Sub("v", "b", "en.v.b", "V (vest) is coming out as B. Let your lower lip touch the upper teeth.", listOf("vest", "best")),
        // Nasals.
        Sub("ŋ", "n", "en.ng.fronting", "NG (sing) is coming out as N. The back of your tongue lifts to the soft palate.", listOf("sing", "sin")),
        Sub("n", "ŋ", "en.n.ng", "N (sin) is coming out as NG. Keep your tongue tip behind your teeth.", listOf("sin", "sing")),
        // Vowels.
        Sub("ɪ", "i", "en.ih.iy", "Short IH (ship) is coming out as long EE (sheep). Relax and shorten it.", listOf("ship", "sheep")),
        Sub("i", "ɪ", "en.iy.ih", "Long EE (sheep) is coming out as short IH (ship). Stretch it and smile.", listOf("sheep", "ship")),
        Sub("æ", "ɛ", "en.ae.eh", "AA (bad) is coming out as EH (bed). Open your jaw wider.", listOf("bad", "bed")),
        Sub("ɛ", "æ", "en.eh.ae", "EH (bed) is coming out as AA (bad). Relax your jaw a little.", listOf("bed", "bad")),
        Sub("ʌ", "ɑ", "en.ah.aa", "UH (cup) is coming out as AH (cop). It's a shorter, more central sound.", listOf("cup", "cop")),
        Sub("ɑ", "ʌ", "en.aa.ah", "AH (cop) is coming out as UH (cup). Open your mouth wider and keep it back.", listOf("cop", "cup")),
        Sub("ʌ", "æ", "en.ah.ae", "UH (cut) is coming out as AA (cat). Don't spread your lips.", listOf("cut", "cat")),
        Sub("æ", "ɑ", "en.ae.aa", "AA (cat) is coming out as AH (cot). Spread your lips and keep the sound front.", listOf("cat", "cot")),
        Sub("ɑ", "æ", "en.aa.ae", "AH (cot) is coming out as AA (cat). Relax the lips and open wider.", listOf("cot", "cat")),
        Sub("ʊ", "u", "en.uh.uw", "Short OO (pull) is coming out as long OO (pool). Shorten it.", listOf("pull", "pool")),
        Sub("u", "ʊ", "en.uw.uh", "Long OO (pool) is coming out as short OO (pull). Stretch it and round firmly.", listOf("pool", "pull")),
        Sub("ɔ", "oʊ", "en.ao.ow", "AW (bought) is coming out as OH (boat). Keep it a pure, open vowel.", listOf("bought", "boat")),
        Sub("oʊ", "ɔ", "en.ow.ao", "OH (boat) is coming out as AW (bought). Glide from O to OO.", listOf("boat", "bought")),
        Sub("eɪ", "ɛ", "en.ey.eh", "AY (late) is coming out as EH (let). Glide from E to EE.", listOf("late", "let")),
        Sub("ɛ", "eɪ", "en.eh.ey", "EH (let) is coming out as AY (late). Keep it short, no glide.", listOf("let", "late")),
    )

    /** Sounds that are simply dropped, where the fix needs its own wording. */
    private val enDeletions: Map<String, Rule> = mapOf(
        "h" to Rule(
            "en.h.dropping",
            "The H in 'house' is silent here. Breathe out before the vowel.",
            listOf("house"),
        ),
    )

    /**
     * French contrasts, weighted towards the errors a Spanish speaker actually
     * makes (nasal vowels, the front rounded /y ø œ/, the uvular R, the two
     * SH sounds). Keys use the symbols espeak-ng emits.
     */
    private val frSubstitutions: List<Sub> = listOf(
        // Nasal vowels: the hardest part of French for most learners.
        Sub("ɑ̃", "ɔ̃", "fr.an.on", "AN (dans) is coming out as ON (don). Keep the lips unrounded and the sound open.", listOf("dans", "don")),
        Sub("ɔ̃", "ɑ̃", "fr.on.an", "ON (bon) is coming out as AN (banc). Round your lips for the nasal.", listOf("bon", "banc")),
        Sub("ɛ̃", "ɑ̃", "fr.in.an", "IN (vin) is coming out as AN (vent). Raise your tongue towards the front.", listOf("vin", "vent")),
        Sub("ɛ̃", "œ̃", "fr.in.un", "IN (vin) is coming out as UN (brun). UN is rounded; IN is not.", listOf("vin", "brun")),
        // Front rounded vowels.
        Sub("y", "u", "fr.u.ou", "U (tu) is coming out as OU (tout). Keep your tongue forward while your lips round.", listOf("tu", "tout")),
        Sub("u", "y", "fr.ou.u", "OU (tout) is coming out as U (tu). Pull your tongue back for OU.", listOf("tout", "tu")),
        Sub("y", "i", "fr.u.i", "U (tu) is losing its lip rounding and becoming I (dit). Round your lips, keep the tongue forward.", listOf("tu", "dit")),
        Sub("ø", "e", "fr.eu.e", "EU (feu) is coming out as É (fée). Round your lips while saying E.", listOf("feu", "fée")),
        Sub("ø", "œ", "fr.eu.closed", "Closed EU (peu) is coming out too open (peur). Push the tongue higher and tense the lips.", listOf("peu", "peur")),
        Sub("œ", "ø", "fr.eu.open", "Open EU (peur) is too closed (peu). Drop your jaw a little.", listOf("peur", "peu")),
        Sub("œ", "ɛ", "fr.eu.eh", "EU (peur) is coming out as È (père). Add lip rounding to the open E.", listOf("peur", "père")),
        // The uvular R.
        Sub("ʁ", "r", "fr.r.rolled", "French R (rue) is made at the back of the throat, not rolled with the tongue tip.", listOf("rue", "roue")),
        Sub("ʁ", "ɹ", "fr.r.english", "French R (rue) is uvular. English R curls the tongue; French rubs at the back.", listOf("rue", "roue")),
        // SH / ZH.
        Sub("ʒ", "ʃ", "fr.zh.sh", "J (je) is losing its voice and becoming CH (che). Keep the buzz.", listOf("je", "cher")),
        Sub("ʃ", "ʒ", "fr.sh.zh", "CH (cher) is coming out as J (je). It must stay voiceless.", listOf("cher", "je")),
        // Nasal palatal.
        Sub("ɲ", "n", "fr.gn.n", "GN (agneau) is coming out as N (anneau). Push the middle of your tongue to the palate.", listOf("agneau", "anneau")),
        Sub("n", "ɲ", "fr.n.gn", "N (anneau) is coming out as GN (agneau). Use only the tongue tip.", listOf("anneau", "agneau")),
        // Glides.
        Sub("ɥ", "w", "fr.ui.oui", "UI (nuit) is coming out as OUI (oui). The glide in nuit is made with the tongue forward.", listOf("nuit", "oui")),
        Sub("w", "ɥ", "fr.oui.ui", "OUI (oui) is coming out as UI (nuit). Pull the tongue back for the glide.", listOf("oui", "nuit")),
        // Voicing (shared with many languages but worth naming here).
        Sub("z", "s", "fr.z.s", "Z (rose) is coming out as S (rosse). Add voice; keep buzzing.", listOf("rose", "rosse")),
        Sub("s", "z", "fr.s.z", "S (rosse) is coming out as Z (rose). Don't let your throat buzz.", listOf("rosse", "rose")),
        Sub("v", "b", "fr.v.b", "V (vous) is coming out as B (bout). Touch your lower lip to your upper teeth.", listOf("vous", "bout")),
        Sub("b", "v", "fr.b.v", "B (bout) is coming out as V (vous). Press both lips together.", listOf("bout", "vous")),
    )

    private val frDeletions: Map<String, Rule> = mapOf(
        "ʁ" to Rule(
            "fr.r.dropping",
            "The final R in words like 'bonjour' is soft but still there. Let the back of your tongue rub.",
            listOf("bonjour"),
        ),
        "ə" to Rule(
            "fr.schwa.dropping",
            "The little E in 'petit' is light but keeps the word from breaking. Add a soft 'uh'.",
            listOf("petit"),
        ),
    )

    /**
     * German contrasts, weighted towards the errors a Spanish speaker actually
     * makes: the front rounded Ü/Ö, vowel length, the ICH and ACH sounds, the
     * voiced S, W said as B and the unstressed endings. Keys use the symbols
     * espeak-ng `de` emits (ʏ, ɑː, ɜ). There is no rule for R on purpose: a
     * rolled, tapped or uvular R are all native German.
     */
    private val deSubstitutions: List<Sub> = listOf(
        // Ü: no Spanish counterpart, so it lands on U or I.
        Sub("yː", "uː", "de.ue.u", "Long Ü (Brüder) is coming out as U (Bruder). Keep the lips rounded but push the tongue forward, as for I.", listOf("Brüder", "Bruder")),
        Sub("ʏ", "ʊ", "de.ue.short.u", "Short Ü (Mütter) is coming out as U (Mutter). Keep the lips rounded but push the tongue forward.", listOf("Mütter", "Mutter")),
        Sub("yː", "iː", "de.ue.i", "Long Ü (Tür) is losing its lip rounding and becoming I (Tier). Round your lips.", listOf("Tür", "Tier")),
        Sub("ʏ", "ɪ", "de.ue.short.i", "Short Ü (küssen) is losing its lip rounding and becoming I (Kissen). Round your lips.", listOf("küssen", "Kissen")),
        // Ö.
        Sub("øː", "eː", "de.oe.e", "Long Ö (lösen) is coming out as E (lesen). Round your lips while saying E.", listOf("lösen", "lesen")),
        Sub("øː", "oː", "de.oe.o", "Long Ö (schön) is coming out as O (schon). Push the tongue forward while the lips stay rounded.", listOf("schön", "schon")),
        Sub("œ", "ɛ", "de.oe.short.e", "Short Ö (können) is coming out as E (kennen). Add lip rounding.", listOf("können", "kennen")),
        Sub("œ", "ɔ", "de.oe.short.o", "Short Ö (Töchter) is coming out as O (Tochter). Push the tongue forward.", listOf("Töchter", "Tochter")),
        // Vowel length and tenseness change the word.
        Sub("iː", "ɪ", "de.long.i", "Long I (bieten) is too short and relaxed, like bitten. Hold it and keep it tense.", listOf("bieten", "bitten")),
        Sub("ɪ", "iː", "de.short.i", "Short I (bitten) is too long and tense, like bieten. Keep it quick and relaxed.", listOf("bitten", "bieten")),
        Sub("uː", "ʊ", "de.long.u", "Long U (Mus) is too short and relaxed, like muss. Hold it and round firmly.", listOf("Mus", "muss")),
        Sub("ʊ", "uː", "de.short.u", "Short U (muss) is too long and tense, like Mus. Keep it quick and relaxed.", listOf("muss", "Mus")),
        Sub("oː", "ɔ", "de.long.o", "Long O (Ofen) is too short and open, like offen. Hold it and close it.", listOf("Ofen", "offen")),
        Sub("ɔ", "oː", "de.short.o", "Short O (offen) is too long and closed, like Ofen. Keep it quick and open.", listOf("offen", "Ofen")),
        Sub("eː", "ɛ", "de.long.e", "Long E (Beet) is too short and open, like Bett. Hold it and close it.", listOf("Beet", "Bett")),
        Sub("ɛ", "eː", "de.short.e", "Short E (Bett) is too long and closed, like Beet. Keep it quick and open.", listOf("Bett", "Beet")),
        Sub("ɑː", "a", "de.long.a", "Long A (Staat) is too short, like Stadt. Hold it twice as long.", listOf("Staat", "Stadt")),
        Sub("a", "ɑː", "de.short.a", "Short A (Stadt) is too long, like Staat. Keep it quick.", listOf("Stadt", "Staat")),
        // CH: the soft ICH sound and the back ACH sound.
        Sub("ç", "ʃ", "de.ich.sch", "The ICH sound (Kirche) is coming out as SCH (Kirsche). Spread your lips and keep the tongue flat near the palate.", listOf("Kirche", "Kirsche")),
        Sub("ç", "x", "de.ich.ach", "The soft ICH sound (ich) is coming out as the back ACH sound, like a Spanish J. Make it at the front, where you say Y.", listOf("ich", "ach")),
        Sub("ç", "k", "de.ich.k", "The ICH sound (ich) is coming out as K. Let the air hiss through instead of stopping it.", listOf("ich", "dich")),
        Sub("x", "k", "de.ach.k", "The ACH sound (Nacht) is coming out as K (nackt). Let the air rub at the back instead of stopping it.", listOf("Nacht", "nackt")),
        // Consonants Spanish merges or lacks.
        Sub("z", "s", "de.z.s", "Voiced S (reisen) is coming out as a hissing S (reißen). Add voice; S before a vowel buzzes in German.", listOf("reisen", "reißen")),
        Sub("v", "b", "de.w.b", "W (Wein) is coming out as B (Bein). Touch your lower lip to your upper teeth and let the air through.", listOf("Wein", "Bein")),
        Sub("b", "v", "de.b.w", "B (Bein) is coming out as W (Wein). Press both lips together, then release.", listOf("Bein", "Wein")),
        Sub("v", "w", "de.w.rounded", "German W (Wasser) is a V sound. Touch your lower lip to your upper teeth; don't round your lips.", listOf("Wasser", "Wein")),
        Sub("ʃ", "s", "de.sch.s", "SCH (Schule, Stein) is coming out as S. Round your lips and pull the tongue back a little.", listOf("Schule", "Stein")),
        Sub("ŋ", "n", "de.ng.n", "NG (singen) is coming out as N (sinnen). The back of your tongue lifts to the soft palate.", listOf("singen", "sinnen")),
        // Unstressed endings are reduced, not read letter by letter.
        Sub("ɜ", "ɛ", "de.er.spelled", "The ending -er (Vater) is a relaxed, A-like vowel, not E plus R.", listOf("Vater", "Mutter")),
        Sub("ə", "e", "de.schwa.e", "The final -e (bitte) is a weak 'uh', not a full E.", listOf("bitte", "danke")),
        Sub("ə", "ɛ", "de.schwa.eh", "The final -e (bitte) is a weak 'uh', not an open E.", listOf("bitte", "danke")),
    )

    private val deDeletions: Map<String, Rule> = mapOf(
        "h" to Rule(
            "de.h.dropping",
            "The H in 'Haus' is silent here, so it sounds like 'aus'. Breathe out before the vowel.",
            listOf("Haus", "aus"),
        ),
    )

    /**
     * Russian contrasts, weighted towards the errors a Spanish speaker actually
     * makes: the hard/soft consonants, Ы, the unstressed vowels read at full
     * value, the hushing Ш/Ж/Щ and the voiced З/В. Keys use the symbols
     * espeak-ng `ru` emits (Ы is y, soft З is ʑ, reduced О is ʌ). Softness is a
     * segment of its own in the alignment, so a soft consonant said hard is a
     * dropped /ʲ/, a deletion.
     */
    private val ruSubstitutions: List<Sub> = listOf(
        // Ы: no Spanish counterpart, so it lands on I or U.
        Sub("y", "i", "ru.y.i", "Ы (быть) is coming out as И (бить). Pull the tongue back and keep the lips unrounded.", listOf("быть", "бить")),
        Sub("y", "u", "ru.y.u", "Ы (мыл) is coming out as У (мул). Spread your lips; only the tongue moves back.", listOf("мыл", "мул")),
        // Vowel reduction: unstressed vowels are not read at full value.
        Sub("ʌ", "o", "ru.o.unreduced", "Unstressed О (молоко) weakens to a short A-like sound. Only the stressed О is a full O.", listOf("молоко", "хорошо")),
        Sub("i", "e", "ru.e.unreduced", "Unstressed Е (сестра) weakens to a short I. Only the stressed Е is a full E.", listOf("сестра", "весна")),
        Sub("ɵ", "e", "ru.yo.e", "Ё (всё) is coming out as Е (все). Round your lips: Ё is a soft consonant plus O.", listOf("всё", "все")),
        // Hushing sounds.
        Sub("ʃ", "s", "ru.sh.s", "Ш (наш) is coming out as С (нас). Pull the tongue back and round your lips a little.", listOf("наш", "нас")),
        Sub("ʃ", "tʃ", "ru.sh.ch", "Ш (шар) is coming out as a CH. Don't start it with a T; let the air flow from the first moment.", listOf("шар", "час")),
        Sub("ʃ", "ɕ", "ru.sh.shch", "Hard Ш (шит) is coming out as soft Щ (щит). Pull the tongue back; Ш is dark.", listOf("шит", "щит")),
        Sub("ɕ", "ʃ", "ru.shch.sh", "Soft Щ (щит) is coming out as hard Ш (шит). Raise the middle of the tongue and hold it longer.", listOf("щит", "шит")),
        Sub("ʒ", "ʃ", "ru.zh.sh", "Ж (жар) is losing its voice and becoming Ш (шар). Keep the buzz.", listOf("жар", "шар")),
        Sub("ʒ", "z", "ru.zh.z", "Ж (жал) is coming out as З (зал). Pull the tongue back and round your lips a little.", listOf("жал", "зал")),
        Sub("ʒ", "dʒ", "ru.zh.j", "Ж (жар) is starting with a D. Let the air flow from the first moment; no stop.", listOf("жар", "жена")),
        // Voicing and the B/V merge.
        Sub("z", "s", "ru.z.s", "З (коза) is coming out as С (коса). Add voice; keep buzzing.", listOf("коза", "коса")),
        Sub("v", "b", "ru.v.b", "В (вас) is coming out as Б (бас). Touch your lower lip to your upper teeth and let the air through.", listOf("вас", "бас")),
        Sub("b", "v", "ru.b.v", "Б (бас) is coming out as В (вас). Press both lips together, then release.", listOf("бас", "вас")),
        Sub("ʑ", "z", "ru.z.hard", "Soft З (взять) is coming out hard. Raise the middle of your tongue to the palate.", listOf("взять", "зал")),
        Sub("x", "k", "ru.kh.k", "Х (ход) is coming out as К (код). Let the air rub at the back instead of stopping it.", listOf("ход", "код")),
    )

    private val ruDeletions: Map<String, Rule> = mapOf(
        "ʲ" to Rule(
            "ru.soft.hard",
            "A soft consonant came out hard: 'мать' sounded like 'мат'. Raise the middle of your tongue to the palate as you say it.",
            listOf("мать", "мат"),
        ),
    )

    private class RuleSet(
        val language: String,
        val substitutions: List<Sub>,
        val deletions: Map<String, Rule>,
    ) {
        /** Keyed by (reference, produced). */
        val byPair: Map<Pair<String, String>, Sub> = substitutions.associateBy { it.target to it.actual }

        /** Same rules keyed without vowel length, for phonemizers that write /iː/. */
        val byPairNoLength: Map<Pair<String, String>, Sub> =
            substitutions.associateBy { stripLength(it.target) to stripLength(it.actual) }

        /** Exact lookup first, then a length-insensitive one for /i/ vs /iː/. */
        fun lookup(expected: String, actual: String): Sub? =
            byPair[expected to actual]
                ?: byPairNoLength[stripLength(expected) to stripLength(actual)]
    }

    private val ruleSets: Map<String, RuleSet> = listOf(
        RuleSet("en", enSubstitutions, enDeletions),
        RuleSet("fr", frSubstitutions, frDeletions),
        RuleSet("de", deSubstitutions, deDeletions),
        RuleSet("ru", ruSubstitutions, ruDeletions),
    ).associateBy { it.language }

    /** The curated set for a supported language, or null when none exists. */
    private fun rulesFor(language: String?): RuleSet? {
        val pack = LanguagePacks.byCodeOrNull(language) ?: return null
        return ruleSets[pack.errorRulesKey]
    }

    /** Every phone a language's rules mention, so tests can hold them to the model vocabulary. */
    internal fun symbols(language: String): Set<String> {
        val rules = rulesFor(language) ?: return emptySet()
        return rules.substitutions.flatMap { listOf(it.target, it.actual) }.toSet() + rules.deletions.keys
    }

    /** The id of every rule of a language, in declaration order. */
    internal fun ruleIds(language: String): List<String> {
        val rules = rulesFor(language) ?: return emptyList()
        return rules.substitutions.map { it.id } + rules.deletions.values.map { it.id }
    }

    /** Curated rule for a (reference, produced) contrast, or null when unknown. */
    fun ruleFor(phoneme: String, actual: String?, language: String = "en"): RuleInfo? {
        val rules = rulesFor(language) ?: return null
        if (actual == null || actual == "-") {
            return rules.deletions[phoneme]?.let { RuleInfo(it.id, it.title, it.examples) }
        }
        val rule = rules.lookup(phoneme, actual) ?: return null
        return RuleInfo(rule.id, rule.title, rule.pair)
    }

    /** Diagnoses the non-perfect substitutions and deletions, worst first. */
    fun analyze(alignment: List<PhonemeMatch>, language: String = "en"): List<PhonemeErrorModel> {
        val rules = rulesFor(language) ?: return emptyList()
        val errors = ArrayList<PhonemeErrorModel>(MAX_ERRORS)
        val seen = mutableSetOf<String>()

        for (match in alignment) {
            if (match.expected.isBlank() || match.expected == "-") continue
            if (match.status == MatchStatus.PERFECT) continue

            val error: PhonemeErrorModel
            if (match.actual == "-") {
                // A word-final release the scorer forgave is CLOSE, not a dropped
                // sound: don't nag the learner about a native-like unreleased stop.
                if (match.status != MatchStatus.MISSED) continue
                val rule = rules.deletions[match.expected]
                error = if (rule != null) {
                    PhonemeErrorModel(
                        phoneme = match.expected,
                        actual = null,
                        kind = PhonemeErrorKindModel.DELETION,
                        ruleId = rule.id,
                        title = rule.title,
                        examples = rule.examples,
                        wordIndex = match.wordIndex,
                    )
                } else {
                    PhonemeErrorModel(
                        phoneme = match.expected,
                        actual = null,
                        kind = PhonemeErrorKindModel.DELETION,
                        ruleId = "${rules.language}.deletion",
                        title = "The sound /${match.expected}/ was dropped.",
                        wordIndex = match.wordIndex,
                    )
                }
            } else {
                // Only claim an error we can explain; an unknown substitution is
                // left to the generic alignment feedback.
                val rule = rules.lookup(match.expected, match.actual) ?: continue
                error = PhonemeErrorModel(
                    phoneme = match.expected,
                    actual = match.actual,
                    kind = PhonemeErrorKindModel.SUBSTITUTION,
                    ruleId = rule.id,
                    title = rule.title,
                    examples = rule.pair,
                    wordIndex = match.wordIndex,
                )
            }

            val key = error.ruleId ?: "${error.phoneme}->${error.actual}"
            if (seen.add(key)) errors.add(error)
            if (errors.size >= MAX_ERRORS) break
        }
        return errors
    }

    private fun stripLength(phone: String): String = phone.removeSuffix("ː")
}
