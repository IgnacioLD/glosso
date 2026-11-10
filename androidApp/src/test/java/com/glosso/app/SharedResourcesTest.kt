package com.glosso.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards for the split between Compose Multiplatform resources (shared UI) and
 * the few Android resources the system or Android-only code reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedResourcesTest {

    private val root: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "shared/src/commonMain/composeResources").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }

    /**
     * The download notification reads the shared UI's words, so the only download
     * strings left in Android resources are the channel's (read by the system).
     */
    @Test
    fun `download words live only in the shared resources`() {
        val androidRes = File(root, "androidApp/src/main/res")
        val leftovers = androidRes.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }!!.toList() }
            .flatMap { file -> Regex("""name="(download_[a-z_]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
        assertEquals(setOf("download_channel_name", "download_channel_description"), leftovers)
    }

    /**
     * Compose Multiplatform only substitutes %N$s and %N$d and only decodes \n, \t,
     * \uXXXX and \\, so Android-only syntax would show up literally on screen.
     */
    @Test
    fun `shared strings use only syntax Compose Multiplatform interprets`() {
        val files = File(root, "shared/src/commonMain/composeResources")
            .listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .flatMap { it.listFiles { f -> f.extension == "xml" }!!.toList() }
        assertTrue(files.size >= 8)
        val value = Regex("""<(?:string|item)\b[^>]*>(.*?)</(?:string|item)>""")
        val problems = files.flatMap { file ->
            value.findAll(file.readText()).map { it.groupValues[1] }.mapNotNull { text ->
                when {
                    "%%" in text -> "${file.parentFile?.name}/${file.name}: %% in [$text]"
                    Regex("""%[ds]""").containsMatchIn(text) -> "${file.parentFile?.name}/${file.name}: unindexed placeholder in [$text]"
                    Regex("""\\['"@?]""").containsMatchIn(text) -> "${file.parentFile?.name}/${file.name}: Android escape in [$text]"
                    else -> null
                }
            }.toList()
        }
        assertEquals(emptyList<String>(), problems)
    }

    /**
     * The splash (Android theme) and the shared UI draw the same logo; the shared
     * copy spells out the light and dark ink the Android one takes from colors.xml.
     */
    @Test
    fun `shared logo matches the Android drawable`() {
        val android = File(root, "androidApp/src/main/res/drawable/ic_glosso_symbol.xml").readText()
        val shared = File(root, "shared/src/commonMain/composeResources")
        assertEquals(
            android.replace("@color/glosso_logo_ink", "#FF0B0B0E"),
            File(shared, "drawable/ic_glosso_symbol.xml").readText(),
        )
        assertEquals(
            android.replace("@color/glosso_logo_ink", "#FFF4F4F6"),
            File(shared, "drawable-dark/ic_glosso_symbol.xml").readText(),
        )
        val light = File(root, "androidApp/src/main/res/values/colors.xml").readText()
        val dark = File(root, "androidApp/src/main/res/values-night/colors.xml").readText()
        assertTrue(light.contains("""<color name="glosso_logo_ink">#FF0B0B0E</color>"""))
        assertTrue(dark.contains("""<color name="glosso_logo_ink">#FFF4F4F6</color>"""))
    }
}
