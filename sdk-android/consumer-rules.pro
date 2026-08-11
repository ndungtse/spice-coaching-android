# MicroCoaching SDK consumer ProGuard rules.
# These rules are applied to the host app (SPICE) when it uses this SDK.

# Keep all public SDK API classes
-keep class com.medtroniclabs.microcoaching.MicroCoachingSDK { *; }
-keep class com.medtroniclabs.microcoaching.MicroCoachingConfig { *; }
-keep class com.medtroniclabs.microcoaching.Language { *; }
-keep class com.medtroniclabs.microcoaching.ModelDownloadStrategy { *; }
-keep class com.medtroniclabs.microcoaching.sdk.** { *; }
-keep class com.medtroniclabs.microcoaching.chat.CoachingChatFragment { *; }
-keep class com.medtroniclabs.microcoaching.data.model.** { *; }

# OpenTelemetry
-dontwarn io.opentelemetry.**
-keep class io.opentelemetry.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# ── MediaPipe GenAI (on-device Gemma inference) ──────────────────────────────
# The .task model is loaded via MediaPipe's LlmInference, whose options are built
# with protobuf-lite. These rules MUST live here (consumer-rules), not in
# proguard-rules.pro: the SDK itself is not minified, so proguard-rules.pro never
# runs — only these consumer rules reach the host app's release (R8) build.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.ai.edge.** { *; }
-dontwarn com.google.ai.edge.**

# protobuf-lite resolves generated-message fields by their ORIGINAL names via
# reflection. R8 renaming them in the host's release build breaks model loading
# at runtime with e.g. "Field modelPath_ for yh.a not found". Keep the generated
# message classes and — critically — their field names.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
