package com.glosso.app

/**
 * The level the app opens on, in priority order: the level of the lesson in
 * progress, else the level the learner picked by hand, else the level of the
 * last finished lesson, else A1. The manual choice wins over the last finished
 * lesson so a deliberate "ALL" or a step down to review survives a cold start.
 */
fun resolveStartupLevel(
    resumeLessonId: String?,
    resumeLevel: String?,
    lastCompletedLevel: String?,
    manualLevel: String?,
): String {
    val fromResume = resumeLevel?.takeIf { it.isNotBlank() && resumeLessonId != null }
    return fromResume
        ?: manualLevel?.takeIf { it.isNotBlank() }
        ?: lastCompletedLevel?.takeIf { it.isNotBlank() }
        ?: "A1"
}

/**
 * The level to keep while the app is open. The user's current choice wins when
 * the content still publishes it; otherwise the first published level. A manual
 * change during a session is therefore never overridden on a reload.
 */
fun levelForReload(currentLevel: String, availableLevels: List<String>): String =
    if (availableLevels.isEmpty() || currentLevel in availableLevels) currentLevel
    else availableLevels.first()

/**
 * The levels to offer, in order, with "ALL" last. A course that publishes a
 * single level gets just that one: "all levels" would be the same list twice.
 */
fun orderedLevels(raw: List<String>): List<String> {
    val distinct = raw.filter { it.isNotBlank() }.distinct().sorted()
    return if (distinct.size <= 1) distinct else distinct + "ALL"
}
