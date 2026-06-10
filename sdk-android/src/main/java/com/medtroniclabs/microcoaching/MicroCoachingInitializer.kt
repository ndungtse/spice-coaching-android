package com.medtroniclabs.microcoaching

import android.content.Context
import androidx.startup.Initializer

/**
 * AndroidX App Startup initializer for the MicroCoaching SDK.
 *
 * This runs automatically when the host app (SPICE) starts — zero boilerplate needed.
 * It pre-warms the database connection so the first chat open is faster.
 *
 * The SDK itself is NOT initialized here (that requires client config from SPICE).
 * The host app must still call [MicroCoachingSDK.Builder.build] in Application.onCreate().
 *
 * To disable auto-initialization (e.g., for testing), add to your AndroidManifest:
 * ```xml
 * <provider
 *     android:name="androidx.startup.InitializationProvider"
 *     android:authorities="${applicationId}.androidx-startup"
 *     tools:node="remove" />
 * ```
 */
class MicroCoachingInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Pre-warm the database connection on a background thread.
        // The SDK singleton is initialized by the host app separately.
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
