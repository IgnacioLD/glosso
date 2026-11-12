import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// The app code shared by Android and iOS (docs/IOS-KMP-PLAN.md). Both targets are
// opt-in in the same way as :engine's Android target, so a host without the tools
// still configures the build:
// - Android needs an SDK (the server image has none): -Pglosso.android=true|false.
// - iOS needs a macOS host for Kotlin/Native's Apple targets: -Pglosso.ios=true|false.
val androidEnabled: Boolean = when {
    hasProperty("glosso.android") -> property("glosso.android").toString().toBoolean()
    else -> System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        rootProject.file("local.properties").exists()
}
val iosEnabled: Boolean = when {
    hasProperty("glosso.ios") -> property("glosso.ios").toString().toBoolean()
    else -> System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
}
// The browser build (docs/WEB.md): needs no host tools, so it is on unless
// -Pglosso.web=false.
val webEnabled: Boolean = findProperty("glosso.web")?.toString()?.toBoolean() ?: true

// What Android reads from BuildConfig, for the web build: the version shown in
// Settings (the Android app's, so both report the same release) and the same
// -PglossoApiBase / -PglossoApiKey inputs androidApp/build.gradle.kts takes.
val webBuildSource = layout.buildDirectory.dir("generated/webBuild")
val generateWebBuild = tasks.register("generateWebBuild") {
    val androidBuild = rootProject.file("androidApp/build.gradle.kts")
    val versionName = Regex("""baseVersionName = "([^"]+)"""").find(androidBuild.readText())!!.groupValues[1]
    val versionCode = Regex("""versionCode = (\d+)""").find(androidBuild.readText())!!.groupValues[1]
    val apiBase = (findProperty("glossoApiBase") as String?) ?: "https://api.glossostudio.com"
    val apiKey = (findProperty("glossoApiKey") as String?).orEmpty()
    val debug = (findProperty("glosso.web.debug") as String?).toBoolean()
    inputs.property("values", listOf(versionName, versionCode, apiBase, apiKey, debug.toString()))
    val out = webBuildSource.map { it.file("com/glosso/app/WebBuild.kt") }
    outputs.dir(webBuildSource)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |package com.glosso.app
            |
            |internal object WebBuild {
            |    const val VERSION_NAME = "$versionName"
            |    const val VERSION_CODE = $versionCode
            |    const val API_BASE = "$apiBase"
            |    const val API_KEY = "$apiKey"
            |    const val DEBUG = $debug
            |}
            |""".trimMargin(),
        )
    }
}

if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

// The vendored decoder (src/nativeInterop/stb_vorbis/README.md) and, per iOS
// target, the SDK and clang triple it is compiled for. 12.0 is Kotlin/Native's own
// minimum for these targets, so the library never raises the app's.
val stbVorbisDir = file("src/nativeInterop/stb_vorbis")
val stbVorbisSlices = mapOf(
    "iosArm64" to ("iphoneos" to "arm64-apple-ios12.0"),
    "iosSimulatorArm64" to ("iphonesimulator" to "arm64-apple-ios12.0-simulator"),
)

kotlin {
    if (androidEnabled) {
        // The plugin is applied imperatively above, so there is no generated `android {}` accessor.
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "com.glosso.shared"
            compileSdk = 36
            minSdk = 26
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            // src/androidMain/res, and the Compose resources packaged as Android assets.
            androidResources { enable = true }
            // Robolectric tests (src/androidHostTest) read those resources.
            withHostTest { isIncludeAndroidResources = true }
            // Compose UI tests of the shared components on a device (src/androidDeviceTest).
            withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
        }
    }
    if (iosEnabled) {
        // The iOS app (phase 4) embeds this as `Shared.framework` and calls
        // MainViewController(). Linking it needs Xcode; compiling the klibs does not.
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "Shared"
                isStatic = true
            }
            // Ogg Vorbis decoding for the content audio, which AVFoundation cannot
            // play: stb_vorbis compiled for this slice and embedded in the klib.
            val slice = stbVorbisSlices.getValue(target.name)
            val outDir = layout.buildDirectory.dir("stb_vorbis/${target.name}")
            val compileStbVorbis = tasks.register<Exec>("compileStbVorbis${target.name.replaceFirstChar(Char::uppercase)}") {
                description = "Compiles the vendored stb_vorbis.c for ${target.name}."
                inputs.dir(stbVorbisDir)
                outputs.dir(outDir)
                commandLine(stbVorbisDir.resolve("build-static.sh").path, slice.first, slice.second, outDir.get().asFile.path)
            }
            target.compilations.getByName("main").cinterops.create("stbvorbis") {
                includeDirs(stbVorbisDir)
                extraOpts("-libraryPath", outDir.get().asFile.path)
                tasks.named(interopProcessingTaskName).configure { dependsOn(compileStbVorbis) }
            }
        }
    }

    if (webEnabled) {
        // The PWA: :shared:wasmJsBrowserDevelopmentRun serves it, and
        // :shared:wasmJsBrowserDistribution builds what is deployed.
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        wasmJs {
            outputModuleName.set("glosso")
            browser {
                commonWebpackConfig { outputFileName = "glosso.js" }
            }
            binaries.executable()
        }
    }

    compilerOptions {
        // PlatformContext is an expect class (a typealias of Context on Android).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api(project(":engine"))
                api(libs.ktor.client.core)
                implementation(libs.compose.multiplatform.runtime)
                // Haptics read the composition's view and feedback (rememberHaptics).
                implementation(libs.compose.multiplatform.ui)
                api(libs.compose.multiplatform.resources)
                // The shared UI: Material 3, ViewModels that survive configuration
                // changes, lifecycle-aware Compose, and images (PNG and SVG) through Coil 3.
                api(libs.compose.multiplatform.material3)
                api(libs.compose.multiplatform.material.icons.extended)
                // material3 1.9.0 asks for ui-backhandler 1.9.1; keep it on the
                // Compose Multiplatform line the rest of the UI uses.
                implementation(libs.compose.multiplatform.ui.backhandler)
                api(libs.lifecycle.viewmodel.multiplatform)
                api(libs.lifecycle.viewmodel.compose.multiplatform)
                api(libs.lifecycle.runtime.compose.multiplatform)
                api(libs.coil.compose)
                implementation(libs.coil.svg)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                api(libs.okio)
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        findByName("androidMain")?.dependencies {
            implementation(libs.ktor.client.android)
            // Already in the app: BackHandler, the permission launcher and the
            // file picker (activity-compose). Google sign-in is not here: it lives
            // in :androidApp's playstore flavor, so the F-Droid build has none.
            implementation(libs.androidx.activity.compose)
            // Images over OkHttp, the transport Coil 2 already used in the app.
            implementation(libs.coil.network.okhttp)
        }
        // Robolectric tests of the shared UI that need Android (Compose UI tests,
        // resources in a locale, SharedPreferences). Pure logic goes in commonTest.
        findByName("androidHostTest")?.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui.test.junit4)
            // Registers the ComponentActivity that createComposeRule launches.
            implementation(libs.compose.ui.test.manifest)
        }
        findByName("androidDeviceTest")?.dependencies {
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.compose.ui.test.manifest)
        }
        // gradle.properties turns the default hierarchy template off (for :engine's
        // onnxMain), so the web and iOS source sets are wired by hand.
        if (webEnabled) {
            getByName("wasmJsMain") {
                dependsOn(commonMain)
                dependencies {
                    // Every Ktor module on the version Coil brings, as on iOS.
                    implementation(project.dependencies.platform(libs.ktor.bom.ios))
                    implementation(libs.ktor.client.js)
                    implementation(libs.coil.network.ktor3)
                    // The browser has no synchronous file system (AppFiles.wasmJs.kt). The
                    // okio Compose brings on the web (3.17), not the app's 3.9.
                    implementation(libs.okio.fakefilesystem)
                    // Content clips are Ogg Vorbis, which Safari cannot decode (glosso-web.mjs).
                    implementation(npm("@wasm-audio-decoders/ogg-vorbis", "0.1.20"))
                }
                kotlin.srcDir(generateWebBuild)
            }
            // No web tests: commonTest reads the real file system, which a page has not.
        }
        if (iosEnabled) {
            val iosMain = create("iosMain") {
                dependsOn(commonMain)
                dependencies {
                    // Every Ktor module on the version Coil brings (libs.versions.toml, ktor-ios).
                    implementation(project.dependencies.platform(libs.ktor.bom.ios))
                    implementation(libs.ktor.client.darwin)
                    // NavigationBackHandler: the edge swipe back (PlatformBackHandler.ios.kt).
                    implementation(libs.navigationevent.compose.multiplatform)
                    implementation(libs.coil.network.ktor3)
                }
            }
            getByName("iosArm64Main").dependsOn(iosMain)
            getByName("iosSimulatorArm64Main").dependsOn(iosMain)
            val iosTest = create("iosTest") {
                dependsOn(commonTest)
            }
            getByName("iosArm64Test").dependsOn(iosTest)
            getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        }
    }
}

if (webEnabled) {
    // FakeFileSystem still uses kotlinx.datetime.Clock, which kotlinx-datetime 0.7
    // (brought by Compose) dropped; its compat build keeps it next to the 0.7 API.
    configurations.matching { it.name.startsWith("wasmJs") }.configureEach {
        resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
    }
    // The two answer cues, served next to the page (FeedbackSounds.wasmJs.kt).
    tasks.named<ProcessResources>("wasmJsProcessResources") {
        from("src/androidMain/res/raw") { include("feedback_*.wav") }
    }
}

// Strings, fonts and images shared by the Android and iOS UI live in
// src/commonMain/composeResources. Android-only resources (manifest label, launcher
// icon, splash theme, notifications) stay in androidApp/res.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.glosso.app.resources"
    generateResClass = always
}

if (iosEnabled) {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        // Pin the simulator (a name or a UDID) when several agents share a Mac:
        // -Pglosso.ios.simulator=<udid>.
        (findProperty("glosso.ios.simulator") as String?)?.let { device.set(it) }
        // The decoder tests read their Ogg fixtures from the checkout; the
        // simulator shares the host file system.
        environment("SIMCTL_CHILD_GLOSSO_VORBIS_FIXTURES", file("src/iosTest/resources/vorbis").path)
    }
}
