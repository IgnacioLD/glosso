package com.glosso.app

import androidx.compose.runtime.Composable
import com.glosso.app.resources.*
import com.glosso.engine.model.PhonemeErrorModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Plain-language explanation of a target sound, so the feedback never leaves two
 * bare IPA symbols side by side. The phoneme is the key; the written IPA stays as
 * a support line for the learner who wants it (directriz de feedback: decir que
 * sonido es, como se articula y una palabra ancla, sin tecnicismos).
 *
 * Keyed by the base phone, so /i/ and /iː/ share one guide, and unknown symbols
 * simply have none and fall back to the curated rule text.
 */
internal object SoundGuide {

    /** Folds the length mark so long and short forms of a vowel share a guide. */
    fun base(phoneme: String): String = phoneme.removeSuffix("ː")

    fun guideFor(phoneme: String): StringResource? = when (base(phoneme)) {
        "θ" -> Res.string.sound_guide_theta
        "ð" -> Res.string.sound_guide_eth
        "s" -> Res.string.sound_guide_s
        "z" -> Res.string.sound_guide_z
        "ʃ" -> Res.string.sound_guide_sh
        "tʃ" -> Res.string.sound_guide_ch
        "dʒ" -> Res.string.sound_guide_j
        "ɹ", "r" -> Res.string.sound_guide_r
        "l" -> Res.string.sound_guide_l
        "w" -> Res.string.sound_guide_w
        "v" -> Res.string.sound_guide_v
        "b" -> Res.string.sound_guide_b
        "ŋ" -> Res.string.sound_guide_ng
        "n" -> Res.string.sound_guide_n
        "ɪ" -> Res.string.sound_guide_ih
        "i" -> Res.string.sound_guide_iy
        "æ" -> Res.string.sound_guide_ae
        "ɛ" -> Res.string.sound_guide_eh
        "ʌ" -> Res.string.sound_guide_ah
        "ɑ" -> Res.string.sound_guide_aa
        "ʊ" -> Res.string.sound_guide_uh
        "u" -> Res.string.sound_guide_uw
        "ɔ" -> Res.string.sound_guide_ao
        "oʊ" -> Res.string.sound_guide_ow
        "eɪ" -> Res.string.sound_guide_ey
        "h" -> Res.string.sound_guide_h
        "ɑ̃" -> Res.string.sound_guide_an
        "ɔ̃" -> Res.string.sound_guide_on
        "ɛ̃" -> Res.string.sound_guide_in
        "œ̃" -> Res.string.sound_guide_un
        "y" -> Res.string.sound_guide_y
        "ø" -> Res.string.sound_guide_eu_closed
        "œ" -> Res.string.sound_guide_eu_open
        "ʁ" -> Res.string.sound_guide_r_fr
        "ʒ" -> Res.string.sound_guide_zh
        "ɲ" -> Res.string.sound_guide_gn
        "ɥ" -> Res.string.sound_guide_ui
        "ə" -> Res.string.sound_guide_schwa
        else -> null
    }

    /** The localized guide, or null when the symbol is not curated. */
    @Composable
    fun text(phoneme: String): String? {
        val res = guideFor(phoneme) ?: return null
        return stringResource(res)
    }
}

/**
 * The two examples shown side by side: what the learner produced (wrong) and
 * what the sound should be (right). [wrongWord] is null for a dropped sound,
 * where there is nothing to contrast, and for a rule that offers a single word.
 */
internal data class SoundPair(val wrongWord: String?, val rightWord: String?)

/** The minimal pair that trains [error], split into the wrong and right example. */
internal fun soundPair(error: PhonemeErrorModel): SoundPair {
    val right = error.examples.firstOrNull()
    val wrong = if (error.actual != null) error.examples.getOrNull(1) else null
    return SoundPair(wrongWord = wrong, rightWord = right)
}
