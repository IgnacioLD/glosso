import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The Android target is opt-in: the server builds in a plain JDK image with no
// Android SDK, where applying AGP would fail at configuration time. It is enabled
// automatically when an SDK is available, and can be forced with -Pglosso.android.
val androidEnabled: Boolean = when {
    hasProperty("glosso.android") -> property("glosso.android").toString().toBoolean()
    else -> System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        rootProject.file("local.properties").exists()
}

// iOS targets compile commonMain for Kotlin/Native (the app's :shared module uses
// the engine). They need a macOS host, so they are on there by default and can be
// forced with -Pglosso.ios. The iOS recognizer (iosMain) calls ONNX Runtime's C
// API through cinterop; see docs/IOS-ENGINE.md.
val iosEnabled: Boolean = when {
    hasProperty("glosso.ios") -> property("glosso.ios").toString().toBoolean()
    else -> System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
}

// ONNX Runtime for iOS is not a Maven artifact: tools/fetch-onnxruntime-ios.sh
// downloads Microsoft's official build (version and SHA-256 pinned in the script)
// into the gitignored data/ dir, and the cinterop embeds its static library in
// the klib, so :shared and the app link it with no extra flags. Override the
// location with -Pglosso.onnxruntimeIosDir.
val ortIosScript = rootProject.file("tools/fetch-onnxruntime-ios.sh")
val ortIosDir: File by lazy {
    (findProperty("glosso.onnxruntimeIosDir") as String?)?.let(::file) ?: run {
        val version = Regex("""ORT_VERSION="([^"]+)"""").find(ortIosScript.readText())?.groupValues?.get(1)
            ?: error("ORT_VERSION not found in $ortIosScript")
        rootProject.file("data/onnxruntime-ios/$version")
    }
}

if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (androidEnabled) {
        // The plugin is applied imperatively above, so there is no generated `android {}` accessor.
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "com.glosso.engine"
            compileSdk = 35
            minSdk = 26
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
    if (iosEnabled) {
        // Each target links the matching slice of the ONNX Runtime static archive.
        listOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-simulator-arm64").forEach { (target, slice) ->
            target.compilations.getByName("main").cinterops.create("onnxruntime") {
                includeDirs(ortIosDir.resolve("include"))
                extraOpts("-libraryPath", ortIosDir.resolve(slice).path)
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // ONNX Runtime has the same Java API on JVM and Android, so the acoustic
        // recognizer is written once here and shared; only the artifact differs
        // (onnxruntime vs onnxruntime-android).
        val onnxMain = create("onnxMain") {
            dependsOn(commonMain)
        }
        val jvmMain = getByName("jvmMain") {
            dependsOn(onnxMain)
            dependencies {
                implementation(libs.onnxruntime)
            }
        }
        findByName("androidMain")?.let { androidMain ->
            androidMain.dependsOn(onnxMain)
            androidMain.dependencies {
                implementation(libs.onnxruntime.android)
            }
        }
        if (iosEnabled) {
            val iosMain = create("iosMain") {
                dependsOn(commonMain)
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

if (iosEnabled) {
    val fetchOnnxRuntimeIos = tasks.register<Exec>("fetchOnnxRuntimeIos") {
        description = "Downloads the pinned ONNX Runtime iOS build (no-op when already present)."
        inputs.file(ortIosScript)
        outputs.dir(ortIosDir)
        commandLine(ortIosScript.path, ortIosDir.path)
    }
    tasks.matching { it.name.startsWith("cinteropOnnxruntime") }.configureEach {
        dependsOn(fetchOnnxRuntimeIos)
    }
}

// Compiling for iOS needs only Kotlin/Native, but linking and running the test
// binary on the simulator needs a full Xcode. Without one (Command Line Tools
// only), those tasks are skipped with a note instead of failing `check`.
if (iosEnabled) {
    val xcodeAvailable: Boolean by lazy {
        runCatching {
            ProcessBuilder("xcrun", "xcodebuild", "-version")
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.readBytes() }
                .waitFor() == 0
        }.getOrDefault(false)
    }
    tasks.matching { it.name.startsWith("linkDebugTestIos") || it.name == "iosSimulatorArm64Test" }
        .configureEach {
            onlyIf("Xcode is installed") {
                xcodeAvailable.also { if (!it) logger.lifecycle("$path skipped: needs Xcode (xcrun xcodebuild)") }
            }
        }
}

// Pin the simulator the iOS tests run on (a name or a UDID), e.g. when several
// agents share a Mac: -Pglosso.ios.simulator=<udid>.
if (iosEnabled) {
    (findProperty("glosso.ios.simulator") as String?)?.let { simulator ->
        tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
            device.set(simulator)
        }
    }
}
