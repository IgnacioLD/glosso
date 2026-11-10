package com.glosso.app

import com.glosso.app.resources.Res
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * In-app attribution catalogue.
 *
 * The About screen cannot link to the repository (it is private), so the full
 * licence list - bundled dependencies, fonts, models, audio and images - travels
 * inside the app as the shared resource `files/licenses.json`. That file is generated and checked
 * by `tools/licensing/collect.py`; `tools/licensing/collect_test.py` fails if a
 * declared dependency has no entry.
 */
@Serializable
data class LicenseComponent(
    val id: String,
    val name: String,
    val author: String? = null,
    val license: String,
    val licenseName: String,
    val url: String? = null,
    val usage: String? = null,
    val distribution: String? = null,
    val licenseText: String? = null,
    val licenseTextUrl: String? = null,
    val notice: String? = null,
)

@Serializable
data class LicenseCatalog(
    val schema: Int,
    @SerialName("generatedBy") val generatedBy: String? = null,
    val texts: Map<String, String> = emptyMap(),
    val components: List<LicenseComponent> = emptyList(),
)

private val catalogJson = Json { ignoreUnknownKeys = true }

fun parseLicenseCatalog(raw: String): LicenseCatalog = catalogJson.decodeFromString(raw)

suspend fun loadLicenseCatalog(): LicenseCatalog =
    parseLicenseCatalog(Res.readBytes("files/licenses.json").decodeToString())

private val PRIVACY_LOCALES = setOf("en", "es", "fr", "de")

/** The privacy policy in [uiLanguage] (the interface language), falling back to English. */
suspend fun loadPrivacyText(uiLanguage: String): String {
    val locale = if (uiLanguage in PRIVACY_LOCALES) uiLanguage else "en"
    return Res.readBytes("files/privacy/privacy-$locale.md").decodeToString()
}
