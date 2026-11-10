package com.glosso.app.audio

/**
 * Choosing the reference clip of an item for the learner's accent.
 *
 * An item's audio is keyed by voice ("en-US-female", "en-GB-male", with ":slow"
 * for the slowed reading). Everything one person says (a drill, a word, a story
 * paragraph) is heard in ONE voice, chosen by the content, not by the learner:
 * when an item was rendered in several voices of the learner's accent, one is
 * picked from the item itself, so every item always sounds the same while a
 * course still mixes voices across items. A dialogue line carries only its
 * speaker's voice, so it plays that one.
 *
 * The accent is what the learner chose to hear and what the wording on screen
 * follows, so it wins: a clip in the learner's accent, and only then any other.
 */
private const val SLOW = ":slow"

/** The accent of a voice key: "en-US" for "en-US-female". */
fun accentOf(voiceKey: String): String = voiceKey.removeSuffix(SLOW).substringBeforeLast('-')

/**
 * The voice key this item is heard in for [accent], or null when it has no
 * normal-speed audio. Among several voices of the accent the pick hangs on the
 * item's own clip paths (stable across sessions, devices and online/offline,
 * since paths are never rewritten), so it never changes between listens.
 */
fun Map<String, String>.voiceFor(accent: String): String? {
    val normal = keys.filter { !it.endsWith(SLOW) }.sorted()
    val candidates = normal.filter { accentOf(it) == accent }.ifEmpty { normal }
    if (candidates.size <= 1) return candidates.firstOrNull()
    val seed = mix(candidates.joinToString("|") { getValue(it) }.hashCode())
    return candidates[seed.mod(candidates.size)]
}

/**
 * Murmur3's finalizer. A path holds the item id once per voice, so the raw
 * String hash of two paths keeps the same low bits for every item and the whole
 * course would land on one voice; mixing spreads it.
 */
private fun mix(hash: Int): Int {
    var h = hash
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb
    h = h xor (h ushr 16)
    return h
}

/** The normal-speed clip for [accent], or null when the item has no normal-speed audio. */
fun Map<String, String>.clipFor(accent: String): String? = voiceFor(accent)?.let { this[it] }

/** The rendered slow clip of the item's voice for [accent], or null: the caller then slows the normal one. */
fun Map<String, String>.slowClipFor(accent: String): String? = voiceFor(accent)?.let { this["$it$SLOW"] }

/** The clip to play at the asked speed: the rendered slow one when there is one, else the normal clip. */
fun Map<String, String>.clipFor(accent: String, slow: Boolean): String? =
    if (slow) slowClipFor(accent) ?: clipFor(accent) else clipFor(accent)

/** A clip and the rate to play it at. */
data class ClipPlayback(val path: String, val rate: Float)

/**
 * What to play for a normal or a slow listen: a rendered slow clip plays as it is,
 * otherwise the normal clip plays at [SLOW_RATE], so content needs only a normal clip.
 */
fun Map<String, String>.playbackFor(accent: String, slow: Boolean): ClipPlayback? {
    if (slow) slowClipFor(accent)?.let { return ClipPlayback(it, 1f) }
    val normal = clipFor(accent) ?: return null
    return ClipPlayback(normal, if (slow) SLOW_RATE else 1f)
}
