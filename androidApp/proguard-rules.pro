# R8 keep rules for the release build.
#
# Release shrinks and obfuscates the whole app. Two things break silently under
# R8: JNI (native code looks classes and methods up by name) and reflection. The
# rules are grouped by the library that needs them; debug is not minified, so a
# missing rule only ever shows up here, in a release run.

# Readable release stack traces: R8 writes mapping.txt next to the APK and these
# attributes let `retrace` map an obfuscated frame back to the source line (R8
# replaces the file name with the mapping's id).
-keepattributes SourceFile,LineNumberTable

# --- ONNX Runtime ---
# ai.onnxruntime.** mirrors the ONNX C++ objects. The runtime is reached from
# two directions: the JNI layer resolves classes and `native` methods by name,
# and TensorInfo/OrtUtil use reflection. Renaming or stripping any of it makes
# the acoustic model fail to load (or crash) the moment the recognizer is
# initialized, which is exactly the offline scoring path. onnxruntime-android
# only ships rules for its telemetry classes, so this is the whole contract. The
# names of every `native` method are kept by proguard-android-optimize.txt.
-keep class ai.onnxruntime.** { *; }

# --- Release logging (OWASP M8/M9) ---
# Release keeps only warnings and errors in logcat, which carry no learner data
# (transport failures, model load errors). Verbose, debug and info calls, and the
# string building for their messages, are removed by R8.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
