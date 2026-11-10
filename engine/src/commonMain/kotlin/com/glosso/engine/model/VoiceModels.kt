package com.glosso.engine.model

import kotlinx.serialization.Serializable

@Serializable
enum class VoiceGender { FEMALE, MALE }

/**
 * A reference voice from the offline TTS engine. [language] is the content
 * language it speaks ("en", "fr") and [accent] is the voice-key prefix the audio
 * maps use ("en-US", "fr-FR"), so a language pack can offer more than one accent
 * without the audio schema changing.
 */
@Serializable
data class VoiceModel(
    val id: String,
    val language: String,
    val accent: String,
    val gender: VoiceGender,
    val name: String,
)

/** A selectable accent for a language, with its display label. */
@Serializable
data class AccentOption(val code: String, val label: String)

/** One side of a dialogue's cast: which voice reads these lines. */
@Serializable
data class VoiceSelection(
    val language: String = "en",
    val accent: String = "en-US",
    val gender: VoiceGender = VoiceGender.MALE,
)

/**
 * The learner's audio preferences. [narrator] reads drills and single-voice
 * content; [speakerA]/[speakerB] cast the two sides of a dialogue, so a learner
 * can mix accents on purpose ("a British man and an American woman").
 */
@Serializable
data class VoiceSettings(
    val narrator: VoiceSelection = VoiceSelection("en", "en-US", VoiceGender.FEMALE),
    val speakerA: VoiceSelection = VoiceSelection("en", "en-US", VoiceGender.MALE),
    val speakerB: VoiceSelection = VoiceSelection("en", "en-GB", VoiceGender.FEMALE),
)
