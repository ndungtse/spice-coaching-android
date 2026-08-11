package com.medtroniclabs.microcoaching.domain.decision

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig

enum class CoachingMode { ONLINE, EDGE, CACHED }

class ModeSelector(private val config: MicroCoachingConfig) {

    /**
     * Returns the appropriate coaching mode for the current device state.
     *
     * @param isNetworkAvailable True when the device has active network connectivity.
     * @param availableRamMb Available device RAM in MB. Pass -1 to skip edge-mode eligibility check.
     */
    fun select(isNetworkAvailable: Boolean, availableRamMb: Long = -1L): CoachingMode {
        config.forcedMode?.let { forced ->
            Log.d(TAG, "Mode: FORCED=$forced (network=$isNetworkAvailable ram=${availableRamMb}MB)")
            return forced
        }
        if (isNetworkAvailable && config.backendUrl.isNotBlank()) {
            Log.d(TAG, "Mode: ONLINE (network=true backendUrl set)")
            return CoachingMode.ONLINE
        }
        if (!isNetworkAvailable && availableRamMb >= EDGE_MIN_RAM_MB && config.modelPath.isNotBlank()) {
            Log.d(TAG, "Mode: EDGE (network=false ram=${availableRamMb}MB >= ${EDGE_MIN_RAM_MB}MB model set)")
            return CoachingMode.EDGE
        }
        val reason = when {
            isNetworkAvailable && config.backendUrl.isBlank() -> "backendUrl not set"
            !isNetworkAvailable && config.modelPath.isBlank() -> "offline + no model path"
            !isNetworkAvailable && availableRamMb < EDGE_MIN_RAM_MB ->
                "offline + insufficient RAM (${availableRamMb}MB < ${EDGE_MIN_RAM_MB}MB)"
            else -> "default"
        }
        Log.d(TAG, "Mode: CACHED ($reason)")
        return CoachingMode.CACHED
    }

    companion object {
        private const val TAG = "ModeSelector"
        const val EDGE_MIN_RAM_MB = 3_000L
    }
}
