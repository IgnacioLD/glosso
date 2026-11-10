import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    // The Kotlin package namespace stays `com.glosso.app`; only the published
    // applicationId is inherited from glosso-studio (`me.shirobyte42.glosso`).
    // The two are independent in Gradle, so the source tree is not refactored and
    // the upgrade path keeps the same `/data/data/` directory. See docs/MERGE-FREE-PRO.md.
    namespace = "com.glosso.app"
    compileSdk = 36

    // Release signing continues the published glosso-studio identity: the same
    // keystore and the same package, so Android installs this build *over* the
    // published app and the data directory survives. Secrets are read from
    // local.properties (never committed) or the SIGNING_* environment variables.
    //
    // The signing config is created only when the keystore is actually present,
    // so a clean clone with just `sdk.dir` still builds: release comes out
    // unsigned instead of failing on the missing secret. `.gitignore` keeps the
    // keystore and local.properties out of the repository.
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }
    val releaseStoreFile = (System.getenv("SIGNING_STORE_FILE") ?: localProperties.getProperty("signing.storeFile"))
        ?.let { rootProject.file(it) }
    val hasReleaseKeystore = releaseStoreFile?.isFile == true
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                keyAlias = System.getenv("SIGNING_ALIAS") ?: localProperties.getProperty("signing.alias")
                keyPassword = System.getenv("SIGNING_KEY_PASS") ?: localProperties.getProperty("signing.keyPass")
                storeFile = releaseStoreFile
                storePassword = System.getenv("SIGNING_STORE_PASS") ?: localProperties.getProperty("signing.storePass")
            }
        }
    }
    val releaseSigning = if (hasReleaseKeystore) signingConfigs.getByName("release") else null

    val baseVersionName = "2.5.4"

    defaultConfig {
        applicationId = "me.shirobyte42.glosso"
        minSdk = 26
        targetSdk = 36
        // Continues the published versionCode (glosso-studio v2.2.8 was 2213), so
        // Play accepts this as an update of the same listing. 2215 is the first
        // build of the beta that ships R8-shrunk, obfuscated and billed. 2216 is
        // already uploaded to the Play draft, so this AAB (targetSdk 36) is 2217.
        // 2219 is the published build; 2220 was built with the old onnxruntime;
        // 2221 is the internal-testing update with onnxruntime 1.30.0 (16 KB).
        // 2222 is 2.5.0: American English, "Your turn" and spaced review.
        // 2223 is 2.5.1: Russian A1 rewritten from Russian culture.
        // 2226 is 2.5.4: the v2 usage statistics and crash reports.
        versionCode = 2226
        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The API host is a build input so a debug build can point at a local
        // server for testing without touching production wiring:
        //   ./gradlew :androidApp:assembleDebug -PglossoApiBase=http://10.0.2.2:8080
        val apiBase = providers.gradleProperty("glossoApiBase").getOrElse("https://api.glossostudio.com")
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")

        // Shared client credential for the scoring oracle (/v1/score), read from
        // local.properties (never committed, key `glosso.apiKey`) or
        // -PglossoApiKey. Empty by default: the app sends no header, so emulators
        // and a server without GLOSSO_API_KEY behave exactly as before. It is a
        // build input only to be able to switch it on without a code change; the
        // value is extractable from the APK, so it is an abuse filter, not auth.
        val apiKey = providers.gradleProperty("glossoApiKey").orNull
            ?: localProperties.getProperty("glosso.apiKey")
            ?: ""
        buildConfigField("String", "API_KEY", "\"$apiKey\"")

        // Sign in with Google (Credential Manager). The value is the **Web**
        // OAuth client id of the Google Cloud project, the same the server
        // verifies ID tokens against (GLOSSO_GOOGLE_CLIENT_ID); it is not a
        // secret, but it is project configuration, so it is a build input:
        //   -PglossoGoogleServerClientId=<id>.apps.googleusercontent.com
        // or `glosso.googleServerClientId` in local.properties. Empty hides
        // "Change phone" (no other way to move the account). How to create it
        // (and register the signing SHA-1): docs/GOOGLE-SIGNIN.md.
        val googleServerClientId = providers.gradleProperty("glossoGoogleServerClientId").orNull
            ?: localProperties.getProperty("glosso.googleServerClientId")
            ?: ""
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${googleServerClientId.trim()}\"")

        // Ship only the locales the app is translated into, so the APK does not
        // carry every AndroidX/Play Services translation.
        resourceConfigurations += listOf("en", "es", "fr", "de")

        // The subscription product id is a build input because it only exists once
        // the app is configured in Play Console. It follows the published
        // applicationId so the listing, the package and the product agree:
        //   ./gradlew :androidApp:assembleDebug -PglossoSubscriptionId=me.shirobyte42.glosso.pro
        val subscriptionId = providers.gradleProperty("glossoSubscriptionId")
            .getOrElse("me.shirobyte42.glosso.pro")
        buildConfigField("String", "SUBSCRIPTION_PRODUCT_ID", "\"$subscriptionId\"")

        // Extra points for rhythm and intonation (engine ProsodyBonus), computed
        // on-device against the item's reference audio. Off by default, like
        // ProsodyBonus.ENABLED_BY_DEFAULT; switch it on for a build with
        //   ./gradlew :androidApp:assembleDebug -PglossoProsodyBonus=true
        val prosodyBonus = providers.gradleProperty("glossoProsodyBonus").getOrElse("false").toBoolean()
        buildConfigField("boolean", "PROSODY_BONUS", prosodyBonus.toString())

        // Languages offered on top of LanguagePacks.offered, for trying a course
        // whose content is not published to everyone yet. Comma-separated codes of
        // packs the engine already has; empty by default, so a normal build offers
        // exactly what the engine says:
        //   ./gradlew :androidApp:assembleDebug -PglossoExtraLanguages=ru
        val extraLanguages = providers.gradleProperty("glossoExtraLanguages").getOrElse("")
        buildConfigField("String", "EXTRA_LANGUAGES", "\"$extraLanguages\"")

        // Store-capture mode: hides the debug-only chrome (fixture bar, onboarding
        // seed shortcut, model comparison) so a screenshot of the real UI is free
        // of debug controls. It also exposes one invisible tap target that injects
        // a scored take, used by tools/store/capture.py to reach the feedback
        // screen on an emulator with no microphone. Debug only, off by default:
        //   ./gradlew :androidApp:assembleDebug -PglossoStoreScreenshots=true
        val storeScreenshots = providers.gradleProperty("glossoStoreScreenshots").getOrElse("false").toBoolean()
        buildConfigField("boolean", "STORE_SCREENSHOTS", storeScreenshots.toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Cloud-vs-on-device score parity: the fixtures and golden live in the
    // default androidTest assets root (src/androidTest/assets/score-parity), so
    // they are packaged into the test APK and never into the release app.

    buildTypes {
        debug {
            // Debug builds use the deterministic fake repository by default, so the
            // whole flow can be exercised without Play Console. Point at the real
            // Play Billing client with -PglossoBilling=real once a license tester
            // build is installed from a Play testing track.
            val fake = providers.gradleProperty("glossoBilling").getOrElse("fake") != "real"
            buildConfigField("boolean", "BILLING_FAKE", fake.toString())

            // Upgrade testing: sign the debuggable build with the published release
            // key so it can be installed *over* glosso-studio and inspected with
            // `run-as me.shirobyte42.glosso`. Off by default so ordinary debug
            // builds keep their throwaway debug key:
            //   ./gradlew :androidApp:installDebug -PglossoDebugReleaseSigning=true
            if (providers.gradleProperty("glossoDebugReleaseSigning").getOrElse("false") == "true" &&
                releaseSigning != null
            ) {
                signingConfig = releaseSigning
            }
        }
        release {
            // Release is production: shrink and obfuscate the whole app. The keep
            // rules that R8 needs (ONNX Runtime's JNI/reflection, kotlinx.serialization,
            // Coil/SVG and the engine) live in proguard-rules.pro; without them the
            // acoustic model fails to load at runtime. Mapping is written next to the
            // APK (build/outputs/mapping/release/mapping.txt) to read stack traces.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Unsigned when no keystore is configured, so a clean clone still builds.
            if (releaseSigning != null) signingConfig = releaseSigning
            // A release build is never allowed to fall back to the fake.
            buildConfigField("boolean", "BILLING_FAKE", "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":shared"))

    // androidx.fragment is not used directly; Play Services drags in 1.0.0/1.1.0
    // and conflict resolution only lands on 1.5.7. Play Console flags the bundles
    // for it, so pin the whole graph to the current stable via a constraint rather
    // than declaring a dependency the app does not need.
    constraints {
        implementation(libs.androidx.fragment)
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    // Coil 3 (images, SVG) comes with :shared, which owns the image loader.
    implementation(libs.billing)
    // Google sign-in (Credential Manager, googleid) comes with :shared's androidMain.
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.coil.compose)
    testImplementation(libs.coil.svg)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.test.manifest)
}

// --- Licensing -----------------------------------------------------------------
// The APK must attribute everything it actually ships, including transitive
// dependencies (Play Services/Firebase arrive through the billing library). The
// declared-dependency list is not enough, so these tasks resolve the real debug
// runtime graph and keep a committed lock for tools/licensing/collect.py, which
// reads each artifact's POM for its licence.
val licenseLock = rootProject.layout.projectDirectory.file("tools/licensing/runtime-classpath.txt")

fun Project.resolvedRuntimeCoordinates(): List<String> =
    configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        .map { "${it.group}:${it.module}:${it.version}" }
        .distinct()
        .sorted()

/**
 * Makes sure every runtime module's POM is in the Gradle cache, where collect.py
 * reads licences from. Gradle skips the POM when a module publishes Gradle module
 * metadata, so without this the catalogue depends on what an earlier build
 * happened to download and comes out different on another machine.
 */
fun Project.fetchRuntimePoms() {
    val ids = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
    dependencies.createArtifactResolutionQuery()
        .forComponents(ids)
        .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
        .execute()
        .resolvedComponents
        .forEach { component -> component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java) }
}

val licensesRuntimeClasspath by tasks.registering {
    group = "licensing"
    description = "Write the resolved debug runtime classpath lock for tools/licensing/collect.py"
    doLast {
        val coordinates = project.resolvedRuntimeCoordinates()
        project.fetchRuntimePoms()
        val file = licenseLock.asFile
        file.parentFile.mkdirs()
        file.writeText(coordinates.joinToString("\n", postfix = "\n"))
        logger.lifecycle("licensing: ${coordinates.size} runtime modules -> ${file.relativeTo(rootProject.projectDir)}")
    }
}

val licensesRuntimeClasspathCheck by tasks.registering {
    group = "licensing"
    description = "Fail if the runtime classpath lock is stale (run :androidApp:licensesRuntimeClasspath)"
    outputs.upToDateWhen { false }
    doLast {
        val coordinates = project.resolvedRuntimeCoordinates()
        project.fetchRuntimePoms()
        val committed = licenseLock.asFile.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
        if (coordinates != committed) {
            throw GradleException(
                "tools/licensing/runtime-classpath.txt is out of date. Refresh the runtime lock and the app catalogue:\n" +
                    "  ./gradlew :androidApp:licensesRuntimeClasspath\n" +
                    "  python3 tools/licensing/collect.py",
            )
        }
    }
}

/**
 * Run a licensing script, echo its output to the Gradle log and fail the build
 * if it exits non-zero. Fail closed: a missing interpreter or a crashed script
 * is a gate failure, not a skip, and the script's own report is surfaced.
 */
fun Project.runLicensingPython(script: File, vararg args: String) {
    val command = listOf("python3", script.absolutePath) + args
    val output = StringBuilder()
    val exit = try {
        val process = ProcessBuilder(command)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().forEachLine { line ->
            output.appendLine(line)
            logger.lifecycle("[licensing] $line")
        }
        process.waitFor()
    } catch (error: Exception) {
        throw GradleException("licensing gate could not run python3 (${error.message}). Is python3 on PATH?")
    }
    if (exit != 0) {
        throw GradleException(
            "licensing gate failed (${script.name} ${args.joinToString(" ")} exited $exit).\n" +
                output.toString().trim().lines().joinToString("\n") { "  $it" } + "\n" +
                "Every artifact that reaches users must be attributed with an allowed licence (no GPL/AGPL, " +
                "non-commercial or unattributed entries): fix it with `python3 tools/licensing/collect.py` " +
                "or review the policy in tools/licensing/collect.py.",
        )
    }
}

val licensesCatalogueCheck by tasks.registering {
    group = "licensing"
    description = "Fail if the in-app licence catalogue is stale, incomplete or carries a prohibited licence"
    dependsOn(licensesRuntimeClasspathCheck)
    outputs.upToDateWhen { false }
    doLast {
        project.runLicensingPython(rootProject.file("tools/licensing/collect.py"), "--check")
        project.runLicensingPython(rootProject.file("tools/licensing/collect_test.py"))
    }
}

tasks.named("preBuild") { dependsOn(licensesRuntimeClasspathCheck, licensesCatalogueCheck) }

// --- Release entitlement guard -------------------------------------------------
// The debug build ships FakeSubscriptionRepository, which starts Glosso Pro
// unlocked. That class lives only in src/debug, so it must be absent from every
// release artifact that can reach a device: the APK and the AAB, which is what
// Play actually publishes. Source audits (:androidApp:test) are weaker than
// looking at the binary, so these tasks unzip the packaged artifact and fail if
// the fake ever leaks into a dex. Each runs right after its packaging task, so
// `assembleRelease` guards the APK and `bundleRelease` guards the AAB. This pins
// that release never carries a client-side unlock; it is not the anti-tamper
// defence, because the client does not consult server verification yet (the
// entitlement limitation is tracked in docs/BILLING.md).
val releaseEntitlementForbidden = "FakeSubscriptionRepository"

fun ByteArray.containsSequence(pattern: ByteArray): Boolean {
    if (pattern.isEmpty() || pattern.size > size) return pattern.isEmpty()
    outer@ for (i in 0..(size - pattern.size)) {
        for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
        return true
    }
    return false
}

/** Dex entry names of [artifact] that carry [forbidden] as a raw string. */
fun dexHitsFor(artifact: File, forbidden: String): List<String> {
    val marker = forbidden.toByteArray(Charsets.UTF_8)
    val hits = mutableListOf<String>()
    ZipFile(artifact).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.name.endsWith(".dex")) continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (bytes.containsSequence(marker)) hits += entry.name
        }
    }
    return hits
}

val verifyReleaseApkEntitlement by tasks.registering {
    group = "verification"
    description = "Fail if the release APK contains the debug fake billing repository"
    dependsOn("packageRelease")
    val artifactDir = layout.buildDirectory.dir("outputs/apk/release")
    inputs.dir(artifactDir).withPropertyName("releaseApkDir")
    outputs.upToDateWhen { false }
    doLast {
        val dir = artifactDir.get().asFile
        val apk = dir.listFiles { file -> file.isFile && file.extension == "apk" }?.firstOrNull()
            ?: throw GradleException("release entitlement guard: no APK in $dir")
        val hits = dexHitsFor(apk, releaseEntitlementForbidden)
        if (hits.isNotEmpty()) {
            throw GradleException(
                "release entitlement guard: ${apk.name} contains the debug fake " +
                    "($releaseEntitlementForbidden in ${hits.joinToString()}). Debug-only billing " +
                    "must never reach release.",
            )
        }
        logger.lifecycle("release entitlement guard: ${apk.name} carries no debug billing code")
    }
}

val verifyReleaseBundleEntitlement by tasks.registering {
    group = "verification"
    description = "Fail if the release AAB (what Play publishes) contains the debug fake billing repository"
    dependsOn("bundleRelease")
    val artifactDir = layout.buildDirectory.dir("outputs/bundle/release")
    inputs.dir(artifactDir).withPropertyName("releaseBundleDir")
    outputs.upToDateWhen { false }
    doLast {
        val dir = artifactDir.get().asFile
        val aab = dir.listFiles { file -> file.isFile && file.extension == "aab" }?.firstOrNull()
            ?: throw GradleException("release entitlement guard: no AAB in $dir")
        val hits = dexHitsFor(aab, releaseEntitlementForbidden)
        if (hits.isNotEmpty()) {
            throw GradleException(
                "release entitlement guard: ${aab.name} contains the debug fake " +
                    "($releaseEntitlementForbidden in ${hits.joinToString()}). Debug-only billing " +
                    "must never reach release.",
            )
        }
        logger.lifecycle("release entitlement guard: ${aab.name} carries no debug billing code")
    }
}

// Both artifacts in one command; the per-variant guards above are what CI and the
// packaging tasks actually run.
val verifyReleaseEntitlement by tasks.registering {
    group = "verification"
    description = "Fail if any release artifact (APK or AAB) contains the debug fake billing repository"
    dependsOn(verifyReleaseApkEntitlement, verifyReleaseBundleEntitlement)
}

tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(verifyReleaseApkEntitlement) }
tasks.matching { it.name == "bundleRelease" }.configureEach { finalizedBy(verifyReleaseBundleEntitlement) }
