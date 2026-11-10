# R8 keep rules for the release build.
#
# Release shrinks and obfuscates the whole app. Two things break silently under
# R8: JNI (native code looks classes and methods up by name) and reflection. The
# rules are grouped by the library that needs them; debug is not minified, so a
# missing rule only ever shows up here, in a release run.

# Readable release stack traces: R8 writes mapping.txt next to the APK and these
# attributes let `retrace` map an obfuscated frame back to the source line.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# kotlinx.serialization reads @Serializable and @SerialName from annotations.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# --- Release entitlement guard ---
# The build fails if the debug fake ever reaches a release artifact:
# verifyReleaseApkEntitlement / verifyReleaseBundleEntitlement (build.gradle.kts)
# unzip the APK/AAB and look for the class name "FakeSubscriptionRepository".
# R8 renames com.glosso.* on release, so without this the guard would keep
# searching for a name that no longer exists and would silently stop protecting
# anything. Keeping the *name* (not the class: it is still shrunk when
# unreachable) is what keeps the guard live.
-keepnames class **FakeSubscriptionRepository

# --- ONNX Runtime ---
# ai.onnxruntime.** mirrors the ONNX C++ objects. The runtime is reached from
# two directions: the JNI layer resolves classes and `native` methods by name,
# and TensorInfo/OrtUtil use reflection. Renaming or stripping any of it makes
# the acoustic model fail to load (or crash) the moment the recognizer is
# initialized, which is exactly the offline scoring path. onnxruntime-android
# ships no consumer rules, so this is the whole contract.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
-dontwarn ai.onnxruntime.**

# --- kotlinx.serialization ---
# Every @Serializable model (engine + app) carries a generated `$$serializer` and
# a `Companion.serializer()`; the runtime looks those up by shape, not by name.
-dontwarn kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Coil + SVG ---
# The image loader is built explicitly (see ImageLoading.kt) and Coil sniffs the
# bytes to pick a decoder, so the SvgDecoder is only reachable through Coil's
# component registry. Keep it, and silence the optional integrations Coil and the
# SVG parser reference but the app does not ship.
-keep class coil.decode.SvgDecoder { *; }
-dontwarn coil.**
-dontwarn com.caverock.androidsvg.**

# --- Networking and coroutines ---
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Release logging (OWASP M8/M9) ---
# Release keeps only warnings and errors in logcat, which carry no learner data
# (transport failures, model load errors). Verbose, debug and info calls, and the
# string building for their messages, are removed by R8.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- Credential Manager (Sign in with Google) ---
# CredentialManager finds its Play Services provider by reflection; stripping
# or renaming it makes getCredential fail with "no provider" only in release.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
