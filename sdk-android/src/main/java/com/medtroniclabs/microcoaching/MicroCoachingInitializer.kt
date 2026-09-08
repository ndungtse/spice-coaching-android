package com.medtroniclabs.microcoaching

import android.content.Context
import androidx.startup.Initializer

/**
 * AndroidX App Startup initializer for the MicroCoaching SDK.
 *
 * This runs automatically when the host app (SPICE) starts.
 *
 * **Currently a no-op.** [create] has an empty body — it is a placeholder for pre-warming the
 * database connection, which is not implemented. Do not describe it as a startup optimisation
 * until it does something.
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
        // Intentionally empty — see the class KDoc. The SDK singleton is initialized by the host
        // app separately, and no pre-warming is implemented here yet.
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
