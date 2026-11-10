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

if (androidEnabled) {
    apply(plugin = "com.android.library")
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
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
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

    compilerOptions {
        // PlatformContext is an expect class (a typealias of Context on Android).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        val commonMain by getting {
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
        val commonTest by getting {
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
        findByName("androidUnitTest")?.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui.test.junit4)
            // Registers the ComponentActivity that createComposeRule launches.
            implementation(libs.compose.ui.test.manifest)
        }
        // gradle.properties turns the default hierarchy template off (for :engine's
        // onnxMain), so the iOS source sets are wired by hand.
        if (iosEnabled) {
            val iosMain by creating {
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
            val iosTest by creating {
                dependsOn(commonTest)
            }
            getByName("iosArm64Test").dependsOn(iosTest)
            getByName("iosSimulatorArm64Test").dependsOn(iosTest)
        }
    }
}

if (androidEnabled) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "com.glosso.shared"
        compileSdk = 36
        defaultConfig {
            minSdk = 26
        }
        // Robolectric reads the Compose resources packaged as Android assets.
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
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
