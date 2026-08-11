package com.medtroniclabs.microcoaching.sdk

/**
 * Push-pattern callback for SPICE to receive SDK data without holding a
 * direct Room dependency on SDK internals.
 *
 * Register via [MicroCoachingSDK.Builder.dataCallback] at init time.
 *
 * All methods have default no-op implementations so SPICE only overrides
 * the ones it needs.
 *
 * Pull-pattern alternative: inject [CoachingDataRepository] via Hilt.
 *
 * Example in SpiceBaseApplication:
 * ```kotlin
 * MicroCoachingSDK.Builder(this)
 *     .dataCallback(object : MicroCoachingDataCallback {
 *         override fun onCoachingEventsReady(events: List<Map<String, Any>>) {
 *             // Forward to SPICE backend if needed
 *             myBackendApi.uploadCoachingEvents(events)
 *         }
 *     })
 *     .build()
 * ```
 */
interface MicroCoachingDataCallback {

    /**
     * Called when coaching events are ready to be forwarded to SPICE's backend.
     * Triggered by the SDK's sync worker after each successful backend sync.
     *
     * @param events List of coaching event maps. Keys match the coaching event schema.
     *   Each map is safe to serialize to JSON for forwarding.
     */
    fun onCoachingEventsReady(events: List<Map<String, Any>>) {}

    /**
     * Called after the SDK's telemetry sync worker completes a sync cycle.
     * @param syncedEventCount Number of events that were successfully synced.
     */
    fun onSyncCompleted(syncedEventCount: Int) {}

    /**
     * Called when the on-device model download state changes.
     * Use this to show progress in SPICE's onboarding UI.
     * @param progressPercent 0–100, or -1 if progress is indeterminate.
     */
    fun onModelDownloadProgress(progressPercent: Int) {}

    /**
     * Called when the model download completes successfully.
     * SPICE can use this to enable AI features in the UI.
     */
    fun onModelReady() {}

    /**
     * Called when the model download fails.
     * @param reason Human-readable failure reason for logging/display.
     */
    fun onModelDownloadFailed(reason: String) {}
}
