package com.glosso.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The attribution list has to travel inside the APK (the repository is private),
 * so these tests pin the shipped `files/licenses.json` (a shared Compose resource)
 * and the renderer's model.
 * The dependency-coverage half lives in `tools/licensing/collect_test.py`, which
 * parses the Gradle build files.
 */
class LicensesTest {

    private fun assetFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val repoRelative = File(dir, "shared/src/commonMain/composeResources/files/licenses.json")
            if (repoRelative.isFile) return repoRelative
            dir = dir.parentFile
        }
        error("licenses.json not found from ${File(".").absolutePath}")
    }

    private fun catalog(): LicenseCatalog = parseLicenseCatalog(assetFile().readText())

    @Test
    fun `the shipped catalogue parses with a schema we understand`() {
        val catalog = catalog()
        assertEquals(2, catalog.schema)
        assertTrue("the catalogue should list many components", catalog.components.size > 20)
    }

    @Test
    fun `every component resolves its licence text or a terms link`() {
        val catalog = catalog()
        catalog.components.forEach { component ->
            if (component.licenseText != null) {
                val text = catalog.texts[component.licenseText]
                assertTrue("missing text for ${component.id}", !text.isNullOrBlank())
            } else {
                assertTrue(
                    "component ${component.id} has no embedded text, URL or notice",
                    !component.licenseTextUrl.isNullOrBlank() || !component.notice.isNullOrBlank(),
                )
            }
        }
    }

    @Test
    fun `google sdks keep their non-Apache licence`() {
        val byId = catalog().components.associateBy { it.id }
        assertFalse("Play Billing is no longer linked", "com.android.billingclient:billing" in byId)
        assertEquals("Android-SDK", byId.getValue("com.google.android.gms:play-services-auth").license)
    }

    @Test
    fun `each font carries its own OFL copyright line`() {
        val catalog = catalog()
        val byId = catalog.components.associateBy { it.id }
        val fonts = mapOf(
            "font:BricolageGrotesque" to "Bricolage Grotesque Project Authors",
            "font:PlusJakartaSans" to "Plus Jakarta Sans Project Authors",
            "font:JetBrainsMono" to "JetBrains Mono Project Authors",
        )
        fonts.forEach { (id, copyright) ->
            val text = catalog.texts.getValue(byId.getValue(id).licenseText!!)
            assertTrue("$id must carry its own OFL, got ${text.take(60)}", text.contains(copyright))
        }
    }

    @Test
    fun `the distributed model, fonts and media are attributed`() {
        val ids = catalog().components.map { it.id }.toSet()
        listOf(
            "model:wav2vec2-lv-60-espeak-cv-ft",
            "data:cmudict",
            "asset:catalogue-images",
            "asset:catalogue-audio",
            "font:BricolageGrotesque",
            "font:PlusJakartaSans",
            "font:JetBrainsMono",
        ).forEach { assertTrue("missing attribution for $it", it in ids) }
    }

    @Test
    fun `nothing points at the private repository`() {
        val raw = assetFile().readText()
        assertFalse("licences must not link to the private repo", raw.contains("github.com/IgnacioLD"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicensesAssetPackagingTest {

    @Test
    fun `the catalogue and privacy policy are packaged as assets`() {
        val catalog = runBlocking { loadLicenseCatalog() }
        assertTrue(catalog.components.isNotEmpty())
        assertTrue(runBlocking { loadPrivacyText("en") }.isNotBlank())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyLocaleTest {

    /** The policy in the configuration's language, as `PrivacyScreen` reads it. */
    private fun privacy() = runBlocking {
        loadPrivacyText(RuntimeEnvironment.getApplication().resources.configuration.locales[0].language)
    }

    @Test
    @Config(qualifiers = "en")
    fun `english privacy is in english`() {
        assertTrue(privacy().contains("Privacy and analytics"))
    }

    @Test
    @Config(qualifiers = "es")
    fun `spanish privacy is in spanish`() {
        assertTrue(privacy().contains("Privacidad"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `french privacy is in french`() {
        assertTrue(privacy().contains("Confidentialité"))
    }

    @Test
    @Config(qualifiers = "de")
    fun `german privacy is in german`() {
        assertTrue(privacy().contains("Datenschutz"))
    }

    @Test
    @Config(qualifiers = "ja")
    fun `an unsupported locale falls back to english`() {
        assertTrue(privacy().contains("Privacy and analytics"))
    }
}
