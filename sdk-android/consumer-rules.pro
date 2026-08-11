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
