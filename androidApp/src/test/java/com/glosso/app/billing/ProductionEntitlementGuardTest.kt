package com.glosso.app.billing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audits the entitlement boundary at the *source* level: the debug build may ship
 * a fake that starts Glosso Pro unlocked, but no production source set (`main`,
 * `release`, any future flavour) may reference the fake or add a runtime switch
 * that grants Pro.
 *
 * This is one of three layers. `ReleaseBillingConfigTest` pins the compile-time
 * guard at runtime, and `:androidApp:verifyReleaseApkEntitlement` /
 * `:androidApp:verifyReleaseBundleEntitlement` inspect the packaged release APK
 * and AAB. Sources, compiled flags and the binary are checked separately because
 * a source-only audit can be bypassed by generated code.
 * Like `ContentGateTest`, it reads the tree instead of exercising composables.
 */
class ProductionEntitlementGuardTest {

    private fun moduleDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main").isDirectory) return dir
            if (File(dir, "androidApp/src/main").isDirectory) return File(dir, "androidApp")
            dir = dir.parentFile
        }
        error("androidApp module dir not found from ${File(".").absolutePath}")
    }

    // The shared UI (`:shared`: commonMain, androidMain, iosMain) ships in the app too.
    private fun productionSourceSets(): List<File> =
        listOf(moduleDir(), File(moduleDir().canonicalFile.parentFile, "shared")).flatMap { module ->
            File(module, "src").listFiles()
                ?.filter { it.isDirectory }
                ?.filterNot { it.name == "debug" }
                ?.filterNot {
                    it.name.startsWith("test") || it.name.startsWith("androidTest") || it.name.endsWith("Test")
                }
                ?.sortedBy { it.path }
                .orEmpty()
        }

    private fun productionKotlinFiles(): List<File> =
        productionSourceSets().flatMap { set ->
            set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    @Test
    fun `production source sets never reference the debug fake`() {
        val offenders = productionKotlinFiles().filter {
            it.readText().contains("FakeSubscriptionRepository")
        }
        assertTrue(
            "the debug fake leaked into a production source set: " +
                offenders.joinToString { it.path },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `production source sets carry no runtime entitlement override`() {
        // Tripwires for a "flip a parameter and get Pro" path. None exists today;
        // if shared code ever grows one, this fails instead of shipping a release
        // where changing a flag unlocks Pro.
        val overrideTokens = listOf(
            "unlockPro", "UnlockPro", "DEBUG_UNLOCK", "devPro", "DEV_PRO",
            "forceEntitled", "forcePro", "grantPro", "entitlementOverride",
            "proOverride", "bypassBilling", "BypassBilling", "fakeEntitlement",
        )
        val offenders = productionKotlinFiles().flatMap { file ->
            val text = file.readText()
            overrideTokens.filter { text.contains(it) }.map { "${it} in ${file.name}" }
        }
        assertTrue(
            "a runtime Pro override reached a production source set: " + offenders.joinToString(),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the release seam stubs the fake hook to null`() {
        // `debugFakeBilling` is the only seam main knows about; release must define
        // it as an inert null stub so there is nothing to wire up. The fake
        // implementation lives only in debug.
        val releaseStub = File(moduleDir(), "src/release/java/com/glosso/app/billing/FakeBilling.kt")
        assertTrue("release FakeBilling stub is missing", releaseStub.isFile)
        assertTrue(
            "release debugFakeBilling must return null",
            releaseStub.readText().contains("= null"),
        )
    }
}
