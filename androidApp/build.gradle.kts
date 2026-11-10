import java.util.Properties
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

    // Two distributions of the same app. `playstore` is the Google Play build and
    // keeps Sign in with Google (Play services). `fdroid` ships no non-free
    // library: no Play services, no googleid, so "Change phone" is hidden, and
    // usage statistics start off until the learner turns them on (F-Droid's
    // Tracking anti-feature). See docs/FDROID.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("playstore") {
            dimension = "distribution"
            buildConfigField("boolean", "USAGE_STATISTICS_DEFAULT", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "USAGE_STATISTICS_DEFAULT", "false")
        }
    }

    // The dependency metadata block AGP adds to the APK is encrypted for Google
    // Play; F-Droid's scanner flags it and it cannot be reproduced. Play reads it
    // from the bundle, which keeps it.
    dependenciesInfo {
        includeInApk = false
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
            // Upgrade testing: sign the debuggable build with the published release
            // key so it can be installed *over* glosso-studio and inspected with
            // `run-as me.shirobyte42.glosso`. Off by default so ordinary debug
            // builds keep their throwaway debug key:
            //   ./gradlew :androidApp:installPlaystoreDebug -PglossoDebugReleaseSigning=true
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
    debugImplementation(libs.compose.ui.tooling)

    // Sign in with Google (Credential Manager + Play services): Google Play build only.
    "playstoreImplementation"(libs.androidx.credentials)
    "playstoreImplementation"(libs.androidx.credentials.play.services.auth)
    "playstoreImplementation"(libs.googleid)

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
// dependencies (Play Services arrives through Google sign-in in the playstore
// flavor). The declared-dependency list is not enough, so these tasks resolve
// the real debug runtime graph of each flavor and keep one committed lock per
// flavor for tools/licensing/collect.py, which reads each artifact's POM for its
// licence. The catalogue lives in :shared, which has no flavors, so it covers
// the union. Each flavor's build checks only its own lock, so the F-Droid
// build still passes after its recipe strips the Play-only dependencies.
val licensedFlavors = listOf("fdroid", "playstore")

fun licenseLock(flavor: String): File = rootProject.file("tools/licensing/runtime-classpath-$flavor.txt")

fun Project.runtimeComponentIds(flavor: String): List<org.gradle.api.artifacts.component.ModuleComponentIdentifier> =
    configurations.getByName("${flavor}DebugRuntimeClasspath").incoming.resolutionResult.allComponents
        .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }

fun Project.resolvedRuntimeCoordinates(flavor: String): List<String> =
    runtimeComponentIds(flavor)
        .map { "${it.group}:${it.module}:${it.version}" }
        .distinct()
        .sorted()

/**
 * Makes sure every runtime module's POM is in the Gradle cache, where collect.py
 * reads licences from. Gradle skips the POM when a module publishes Gradle module
 * metadata, so without this the catalogue depends on what an earlier build
 * happened to download and comes out different on another machine.
 */
fun Project.fetchRuntimePoms(flavor: String) {
    dependencies.createArtifactResolutionQuery()
        .forComponents(runtimeComponentIds(flavor))
        .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
        .execute()
        .resolvedComponents
        .forEach { component -> component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java) }
}

val licensesRuntimeClasspath by tasks.registering {
    group = "licensing"
    description = "Write the resolved debug runtime classpath lock of every flavor for tools/licensing/collect.py"
    doLast {
        licensedFlavors.forEach { flavor ->
            val coordinates = project.resolvedRuntimeCoordinates(flavor)
            project.fetchRuntimePoms(flavor)
            val file = licenseLock(flavor)
            file.parentFile.mkdirs()
            file.writeText(coordinates.joinToString("\n", postfix = "\n"))
            logger.lifecycle("licensing: ${coordinates.size} $flavor runtime modules -> ${file.relativeTo(rootProject.projectDir)}")
        }
    }
}

val licensesRuntimeClasspathChecks = licensedFlavors.associateWith { flavor ->
    tasks.register("licensesRuntimeClasspathCheck${flavor.replaceFirstChar(Char::uppercase)}") {
        group = "licensing"
        description = "Fail if the $flavor runtime classpath lock is stale (run :androidApp:licensesRuntimeClasspath)"
        outputs.upToDateWhen { false }
        doLast {
            val coordinates = project.resolvedRuntimeCoordinates(flavor)
            project.fetchRuntimePoms(flavor)
            val lock = licenseLock(flavor)
            val committed = lock.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()
            if (coordinates != committed) {
                throw GradleException(
                    "${lock.relativeTo(rootProject.projectDir)} is out of date. Refresh the runtime locks and the app catalogue:\n" +
                        "  ./gradlew :androidApp:licensesRuntimeClasspath\n" +
                        "  python3 tools/licensing/collect.py",
                )
            }
        }
    }
}

val licensesRuntimeClasspathCheck by tasks.registering {
    group = "licensing"
    description = "Fail if any flavor's runtime classpath lock is stale"
    dependsOn(licensesRuntimeClasspathChecks.values)
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
    outputs.upToDateWhen { false }
    doLast {
        project.runLicensingPython(rootProject.file("tools/licensing/collect.py"), "--check")
        project.runLicensingPython(rootProject.file("tools/licensing/collect_test.py"))
    }
}

tasks.named("preBuild") { dependsOn(licensesCatalogueCheck) }
licensedFlavors.forEach { flavor ->
    val check = licensesRuntimeClasspathChecks.getValue(flavor)
    val variantPrefix = "pre${flavor.replaceFirstChar(Char::uppercase)}"
    tasks.matching { it.name.startsWith(variantPrefix) && it.name.endsWith("Build") }.configureEach { dependsOn(check) }
}
