# ════════════════════════════════════════════════════════════════════════════
# CineCamera — ProGuard / R8 Rules
# ════════════════════════════════════════════════════════════════════════════
#
# R8 is used in full mode (default with AGP 8+). These rules supplement the
# auto-generated consumer rules from each library AAR.
#
# Principle: keep only what is strictly required. Aggressive shrinking
# reduces APK size and makes reverse engineering harder.

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
-keepclassmembers,allowobfuscation class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ── JNI — Native methods must not be renamed ─────────────────────────────────
# The native C++ layer looks up Kotlin methods by name via JNI.
# Any class with native methods or called from C++ must be kept verbatim.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.cinecamera.camera.CameraEngineImpl      { native <methods>; }
-keep class com.cinecamera.encoding.EncodingEngineImpl  { native <methods>; }
-keep class com.cinecamera.imageprocessing.ImageProcessingEngineImpl { native <methods>; }
-keep class com.cinecamera.stabilization.StabilizationEngineImpl    { native <methods>; }
-keep class com.cinecamera.streaming.SRTStreamingController         { native <methods>; }
-keep class com.cinecamera.streaming.RTMPStreamingEngine            { native <methods>; }
-keep class com.cinecamera.recovery.RecoveryEngine                  { native <methods>; }

# ── Kotlin coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Kotlin serialization / data classes used in JSON ────────────────────────
# Gson/Moshi reflect on data class field names — they must not be obfuscated.
-keepclassmembers class com.cinecamera.preset.CameraPreset          { *; }
-keepclassmembers class com.cinecamera.preset.CameraPresetConfig    { *; }
-keepclassmembers class com.cinecamera.recovery.SessionConfig       { *; }
-keepclassmembers class com.cinecamera.telemetry.SessionReport      { *; }
-keepclassmembers class com.cinecamera.telemetry.MetricSample       { *; }

# ── Google Play Billing ──────────────────────────────────────────────────────
-keep class com.android.billingclient.** { *; }

# ── Room database entities and DAOs ─────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *              { *; }
-keep @androidx.room.Dao interface *             { *; }

# ── MediaCodec / Camera2 callbacks ─────────────────────────────────────────
# These are called reflectively by the Android framework.
-keep class * implements android.hardware.camera2.CameraCaptureSession$CaptureCallback { *; }
-keep class * implements android.hardware.camera2.CameraDevice$StateCallback           { *; }
-keep class * implements android.media.MediaCodec$Callback                             { *; }

# ── Timber ───────────────────────────────────────────────────────────────────
-dontwarn org.jetbrains.annotations.**

# ── OkHttp / Retrofit ────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes Exceptions

# ── Debug symbols — strip in release, keep for crash symbolication ──────────
# Native .so debug symbols are stripped by the NDK build; Java stack traces
# are preserved via the mapping.txt uploaded to Play Console.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
