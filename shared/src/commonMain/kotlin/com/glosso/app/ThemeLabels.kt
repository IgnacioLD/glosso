package com.glosso.app

import androidx.compose.runtime.Composable
import com.glosso.app.resources.*
import com.glosso.engine.model.ContentManifest
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Every theme's display name for the given UI language, keyed by id. The server
 * owns the vocabulary via the manifest, so a filter list built from content shows
 * the published name instead of a guess made inside the app. A theme without a
 * label for that language is left out, so [themeLabel] can fall back to the
 * local resource name.
 *
 * When the learner studies an accent of the UI language itself (American English
 * with the app in English), a label published for that accent wins: "Vacation"
 * instead of "Holidays".
 */
fun ContentManifest.themeLabels(uiLanguage: String, accent: String = VoicePreferences.accent): Map<String, String> =
    themes.mapNotNull { theme ->
        val own = theme.labels[accent]?.takeIf { accent.startsWith("$uiLanguage-", ignoreCase = true) && it.isNotBlank() }
        (own ?: theme.labels[uiLanguage]?.takeIf { it.isNotBlank() })?.let { theme.id to it }
    }.toMap()

/**
 * Local names for the known theme ids, used when the manifest has no label for
 * the current UI language. Unknown ids still degrade to a readable form of the id
 * ("news-culture" -> "Culture").
 */
val themeFallbackRes: Map<String, StringResource> = mapOf(
    "family" to Res.string.theme_family,
    "travel" to Res.string.theme_travel,
    "city" to Res.string.theme_city,
    "work" to Res.string.theme_work,
    "food" to Res.string.theme_food,
    "health" to Res.string.theme_health,
    "shopping" to Res.string.theme_shopping,
    "time" to Res.string.theme_time,
    "education" to Res.string.theme_education,
    "society" to Res.string.theme_society,
    "environment" to Res.string.theme_environment,
    "science" to Res.string.theme_science,
    "culture" to Res.string.theme_culture,
    "business" to Res.string.theme_business,
    "friends" to Res.string.theme_friends,
    "home" to Res.string.theme_home,
    "daily-routine" to Res.string.theme_daily_routine,
    "clothes" to Res.string.theme_clothes,
    "weather" to Res.string.theme_weather,
    "feelings" to Res.string.theme_feelings,
    "transport" to Res.string.theme_transport,
    "restaurant" to Res.string.theme_restaurant,
    "school" to Res.string.theme_school,
    "hobbies" to Res.string.theme_hobbies,
    "pets" to Res.string.theme_pets,
    "greetings" to Res.string.theme_greetings,
    "invitations" to Res.string.theme_invitations,
    "directions" to Res.string.theme_directions,
    "airport" to Res.string.theme_airport,
    "hotel" to Res.string.theme_hotel,
    "emergencies" to Res.string.theme_emergencies,
    "office" to Res.string.theme_office,
    "money" to Res.string.theme_money,
    "holidays" to Res.string.theme_holidays,
    "sports" to Res.string.theme_sports,
    "technology" to Res.string.theme_technology,
    "nature" to Res.string.theme_nature,
    "apologies" to Res.string.theme_apologies,
    "complaints" to Res.string.theme_complaints,
    "email" to Res.string.theme_email,
    "gaming" to Res.string.theme_gaming,
    "job-interview" to Res.string.theme_job_interview,
    "movies" to Res.string.theme_movies,
    "music" to Res.string.theme_music,
    "entertainment" to Res.string.theme_entertainment,
    "history" to Res.string.theme_history,
    "scifi" to Res.string.theme_scifi,
    "opinions" to Res.string.theme_opinions,
    "phone-calls" to Res.string.theme_phone_calls,
    "plans" to Res.string.theme_plans,
    "reading" to Res.string.theme_reading,
    "small-talk" to Res.string.theme_small_talk,
    "storytelling" to Res.string.theme_storytelling,
    "university" to Res.string.theme_university,
)

/**
 * The manifest label for [theme] in the already-resolved [labels] map, or null
 * when the server published none for the current UI language. Pure, so the
 * fallback chain can be tested without Android resources.
 */
fun themeLabelFrom(theme: String, labels: Map<String, String>): String? =
    labels[theme]?.takeIf { it.isNotBlank() }

/**
 * A readable form of an unknown theme id, so a theme the app has never heard of
 * still reads as words instead of a slug ("study-plan" -> "Study Plan").
 */
fun readableThemeId(theme: String): String =
    theme.removePrefix("news-").split("-").joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

/**
 * Display name for a theme id: the manifest label for the current UI language
 * when there is one, otherwise a localized name for known ids and, failing that,
 * a readable form of the id.
 */
@Composable
fun themeLabel(theme: String, labels: Map<String, String> = emptyMap()): String {
    themeLabelFrom(theme, labels)?.let { return it }
    val last = theme.substringAfterLast("-")
    themeFallbackRes[theme]?.let { return stringResource(it) }
    themeFallbackRes[last]?.let { return stringResource(it) }
    return readableThemeId(theme)
}
