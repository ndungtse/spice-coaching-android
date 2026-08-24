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

# ── LiteRT-LM (on-device inference) ──────────────────────────────────────────
# These rules MUST live here (consumer-rules), not in proguard-rules.pro: the SDK
# itself is not minified, so proguard-rules.pro never runs — only these consumer
# rules reach the host app's release (R8) build.
#
# The engine crosses into its native layer through JNI callbacks, which R8 cannot
# trace, so renaming or removing anything here breaks inference at runtime.
-keep class com.google.ai.edge.** { *; }
-dontwarn com.google.ai.edge.**

# Every message is serialised to JSON on its way to JNI, by field name.
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
