package com.medtroniclabs.microcoaching.domain.system

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Device-class probe used to decide whether the SDK can host the on-device
 * Gemma model.
 *
 * The Gemma 3-1B `.task` file is ~1.1 GB on disk and pulls ~600 MB of resident
 * RAM into the MediaPipe GenAI runtime. On Samsung Tab A-class hardware
 * (~2 GB total RAM) the system OOM-kills the host process before inference
 * completes. On those devices the chat runs in retrieval-only mode — BM25
 * lookup over `ModuleKnowledgeIndex` + `serveFallback(...)` of the clinician-
 * authored card body — with no LLM round-trip.
 *
 * The 3 GB threshold mirrors [com.medtroniclabs.microcoaching.domain.decision.ModeSelector]
 * `EDGE_MIN_RAM_MB = 3_000L`, the existing cut-off used by EDGE vs CACHED
 * mode selection.
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
