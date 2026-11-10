package com.glosso.app

import com.glosso.app.resources.*
import com.glosso.engine.model.ProsodyBonusModel
import org.jetbrains.compose.resources.StringResource

/** A dimension of the rhythm-and-intonation bonus, with its label and its tip. */
internal enum class ProsodyDimension(val label: StringResource, val tip: StringResource) {
    RHYTHM(Res.string.prosody_rhythm, Res.string.prosody_tip_rhythm),
    PAUSES(Res.string.prosody_pauses, Res.string.prosody_tip_pauses),
    STRESS(Res.string.prosody_stress, Res.string.prosody_tip_stress),
    INTONATION(Res.string.prosody_intonation, Res.string.prosody_tip_intonation),
    TEMPO(Res.string.prosody_tempo, Res.string.prosody_tip_tempo),
}

/** Below this a dimension is worth a tip; at or above it the take follows the audio. */
internal const val PROSODY_TIP_BELOW = 0.8f

/**
 * The measured dimensions shown as meters, in reading order. Tempo is left out
 * of the meters (it is lenient and nearly always full) and only surfaces as a tip.
 */
internal fun prosodyDimensions(prosody: ProsodyBonusModel): List<Pair<ProsodyDimension, Float>> = buildList {
    add(ProsodyDimension.RHYTHM to prosody.rhythm)
    prosody.pauses?.let { add(ProsodyDimension.PAUSES to it) }
    add(ProsodyDimension.STRESS to prosody.stress)
    prosody.intonation?.let { add(ProsodyDimension.INTONATION to it) }
}

/**
 * The weakest measured dimension, when one is weak enough to be worth a tip. A
 * take that already earned every point gets praise, not a correction.
 */
internal fun prosodyFocus(prosody: ProsodyBonusModel): ProsodyDimension? {
    if (prosody.points >= prosody.maxPoints) return null
    return (prosodyDimensions(prosody) + (ProsodyDimension.TEMPO to prosody.tempo))
        .filter { it.second < PROSODY_TIP_BELOW }
        .minByOrNull { it.second }
        ?.first
}
