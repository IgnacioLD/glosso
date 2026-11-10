package com.glosso.engine.drills

import com.glosso.engine.g2p.LexiconG2P
import com.glosso.engine.model.DrillModel
import com.glosso.engine.phonetics.PhoneticComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drill gate against German and Russian lexicons. The entries are what
 * `tools/content/phonemize.py` writes with espeak-ng 1.52 for these words.
 */
class GermanRussianDrillValidatorTest {

    private val german = LexiconG2P.fromLines(
        listOf(
            "meine mˈaɪnə", "brüder bɾˈyːdɜ", "bruder bɾˈuːdɜ", "üben ˈyːbən", "früh frˈyː",
            "ich ɪç", "möchte mˈœçtə", "nicht nˈɪçt", "in ˈɪn", "die diː",
            "kirche kˈɪɾçə", "kirsche kˈɪɾʃə",
            "fünf fˈʏnf", "mütter mˈʏtɜ", "mutter mˈʊtɜ", "sind zɪnt", "dick dˈɪk",
            "und ˈʊnt", "glücklich ɡlˈʏklɪç", "heute hˈɔøtə",
        ),
    )

    private val russian = LexiconG2P.fromLines(
        listOf(
            "мать mˈɑtʲ", "мат mˈɑt", "хочет xˈotʃit", "читать tʃitˈɑtʲ",
            "ты tˈy", "хочешь xˈotʃiʃ", "быть bytʲ", "бить bʲˈitʲ", "дома dˈoma",
        ),
    )

    private fun drill(
        language: String,
        text: String,
        target: String,
        contrast: String?,
        focus: List<String>,
    ) = DrillModel(
        id = "$language.d1",
        language = language,
        theme = "family",
        level = "A1",
        target = target,
        contrast = contrast,
        focus = focus,
        text = text,
    )

    @Test
    fun `a german u-umlaut pair passes`() {
        val result = DrillValidator.validate(
            drill("de", "Meine Brüder üben früh.", "yː", "uː", listOf("Brüder", "Bruder")),
            german,
        )
        assertTrue(result.ok, "unexpected problems: ${result.problems}")
        assertEquals(3, result.targetHits)
    }

    @Test
    fun `a german ich-sch pair passes`() {
        val result = DrillValidator.validate(
            drill("de", "Ich möchte nicht in die Kirche.", "ç", "ʃ", listOf("Kirche", "Kirsche")),
            german,
        )
        assertTrue(result.ok, "unexpected problems: ${result.problems}")
        assertEquals(4, result.targetHits)
    }

    @Test
    fun `an everyday german word is not caught by the english profanity list`() {
        val text = "Fünf Mütter sind dick und glücklich."
        val de = DrillValidator.validate(drill("de", text, "ʏ", "ʊ", listOf("Mütter", "Mutter")), german)
        assertTrue(de.ok, "unexpected problems: ${de.problems}")
        // The same word in an English drill is still refused.
        val en = DrillValidator.validate(drill("en", text, "ʏ", "ʊ", listOf("Mütter", "Mutter")), german)
        assertTrue(en.problems.any { it.code == "text.banned" })
    }

    @Test
    fun `a german pair that contrasts another sound fails`() {
        val result = DrillValidator.validate(
            drill("de", "Meine Brüder üben früh.", "yː", "iː", listOf("Brüder", "Bruder")),
            german,
        )
        assertTrue(result.problems.any { it.code == "focus.pair" }, "problems: ${result.problems}")
    }

    @Test
    fun `a russian hard-soft pair passes with the soft consonant as one phone`() {
        // мать / мат differ in exactly one phone, tʲ vs t.
        val result = DrillValidator.validate(
            drill("ru", "Мать хочет читать.", "tʲ", "t", listOf("мать", "мат")),
            russian,
        )
        assertTrue(result.ok, "unexpected problems: ${result.problems}")
        assertEquals(2, result.targetHits)
    }

    @Test
    fun `yery and i are one contrast together with the hardness of the consonant before them`() {
        // быть / бить also differ in the softness of the first consonant. The two
        // cannot be separated in Russian, so the pair is accepted with or without
        // a declared contrast.
        val paired = DrillValidator.validate(
            drill("ru", "Ты хочешь быть дома?", "y", "i", listOf("быть", "бить")),
            russian,
        )
        assertTrue(paired.ok, "unexpected problems: ${paired.problems}")

        val unpaired = DrillValidator.validate(
            drill("ru", "Ты хочешь быть дома?", "y", null, listOf("быть", "бить")),
            russian,
        )
        assertTrue(unpaired.ok, "unexpected problems: ${unpaired.problems}")
        assertEquals(2, unpaired.targetHits)
    }

    @Test
    fun `content phones keep soft consonants and the german eu diphthong whole`() {
        assertEquals(listOf("m", "ɑ", "tʲ"), PhoneticComparator.contentPhones("mˈɑtʲ"))
        assertEquals(listOf("ɭʲ", "u", "bʲ", "i", "tʲ"), PhoneticComparator.contentPhones("ɭʲubʲˈitʲ"))
        assertEquals(listOf("h", "ɔø", "t", "ə"), PhoneticComparator.contentPhones("hˈɔøtə"))
        // English content is parsed exactly as before.
        assertEquals(listOf("θ", "ɪ", "ŋ", "k"), PhoneticComparator.contentPhones("θˈɪŋk"))
        assertEquals(listOf("k", "æ", "t", "s"), PhoneticComparator.contentPhones("kˈæts"))
    }

    private val russianPairs = LexiconG2P.fromLines(
        listOf(
            "мы mˈy", "ми mʲˈɪ", "это ˈɛtʌ", "ты tˈy", "отец ʌtʲˈets", "цвет tsvʲˈet", "свет svʲˈet",
            "какой kakˈoj", "окно ʌknˈo", "окна ˈokna",
        ),
    )

    @Test
    fun `a russian hard and soft pair passes although the vowel changes with the consonant`() {
        val result = DrillValidator.validate(
            drill("ru", "Э\u0301то мы, а ты?", "y", "ɪ", listOf("мы", "ми")),
            russianPairs,
        )
        assertTrue(result.ok, "unexpected problems: ${result.problems}")
    }

    @Test
    fun `the russian affricate ts is one phone - so tsvet and svet are a minimal pair`() {
        val result = DrillValidator.validate(
            drill("ru", "Оте\u0301ц, како\u0301й э\u0301то цвет?", "ts", "s", listOf("цвет", "свет")),
            russianPairs,
        )
        assertTrue(result.ok, "unexpected problems: ${result.problems}")
        assertEquals(listOf("k", "æ", "t", "s"), PhoneticComparator.contentPhones("kˈæts", "en"))
    }

    @Test
    fun `two words that differ in two unrelated sounds are still rejected`() {
        val result = DrillValidator.validate(
            drill("ru", "Э\u0301то окно\u0301, а там окно\u0301.", "ʌ", "o", listOf("окно\u0301", "о\u0301кна")),
            russianPairs,
        )
        assertTrue(result.problems.any { it.code == "focus.pair" }, "expected a pair problem: ${result.problems}")
    }
}
