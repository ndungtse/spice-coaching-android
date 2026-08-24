package com.medtroniclabs.microcoaching.domain.system

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Device-class probe used to decide whether the SDK *may* host the on-device model.
 *
 * Eligibility, not a decision: hosting the model also requires the user to have opted in
 * (see [com.medtroniclabs.microcoaching.MicroCoachingSDK.localModelEnabled]). Devices that
 * fail this probe never see the offer, and answer from retrieval alone — BM25 over
 * `ModuleKnowledgeIndex`, serving the clinician-authored card body.
 *
 * The threshold guards against the host process being OOM-killed mid-inference: loading a
 * model maps its weights and the inference runtime holds them resident alongside
 * SPICE's own footprint, which on ~2 GB Samsung Tab A-class hardware is enough to lose the
 * process.
 *
 * The 3 GB figure mirrors [com.medtroniclabs.microcoaching.domain.decision.ModeSelector]'s
 * `EDGE_MIN_RAM_MB`, and was chosen against a model several times larger than the one now
 * shipped by default. It is therefore conservative rather than measured: raising it costs
 * eligible devices, and lowering it risks the OOM it exists to prevent, so it should move
 * only on the strength of memory measurements on the smallest supported hardware.
 */
object DeviceCapability {

    /**
     * Minimum total system RAM (bytes) required to run the on-device Gemma
     * model alongside SPICE's own footprint. Anything below falls back to
     * retrieval-only chat.
     */
    const val MIN_RAM_BYTES_FOR_FULL_MODE: Long = 3L * 1024L * 1024L * 1024L

    /**
     * `true` when the device's reported total memory falls below
     * [MIN_RAM_BYTES_FOR_FULL_MODE]. Backed by
     * [ActivityManager.MemoryInfo.totalMem] which reflects the physical RAM
     * the kernel exposes — stable for the lifetime of the process, so callers
     * can memoise the result.
     */
    fun isLowEndDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        Log.d("DeviceCapability", "Total memory: ${info.totalMem}")
        return info.totalMem < MIN_RAM_BYTES_FOR_FULL_MODE
    }
}
