package com.medtroniclabs.microcoaching.sdk.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the SDK's connectivity signal: the observable [available] flow, a point-in-time
 * [isAvailable] check, and the `ConnectivityManager.NetworkCallback` lifecycle. On a
 * offline→online transition it invokes [onRestored] (the facade wires this to flush telemetry
 * + trigger sync).
 *
 * Extracted verbatim from `MicroCoachingSDK` (behaviour-preserving). Construction is inert —
 * nothing registers until [register] is called from the facade's init.
 */
internal class SdkNetworkMonitor(
    private val context: Context,
    private val onRestored: () -> Unit,
) {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var networkLost = false

    /**
     * Which networks are currently up. Callbacks are per-network and Android runs several at
     * once during a handover, so only losing the last one means offline — see
     * [NetworkAvailabilityTracker].
     */
    private val tracker = NetworkAvailabilityTracker()

    private val _available = MutableStateFlow(true)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /** Point-in-time connectivity check (active network has validated INTERNET capability). */
    fun isAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun register() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Re-registration starts from a clean slate; registration replays onAvailable for
        // every currently-matching network, so the tracker refills immediately.
        tracker.clear()

        // If the SDK initialises while OFFLINE, pre-set `networkLost = true` so the next
        // `onAvailable` callback triggers a restore. The previous implementation defaulted to
        // false → the first `onAvailable` (which always fires shortly after registration to
        // report the current network) was silently ignored, and pending events shipped from
        // yesterday's session sat in Room until the 15-min periodic worker fired.
        if (cm.activeNetwork == null) {
            networkLost = true
            _available.value = false
            Log.d(TAG, "Network callback registering while offline — primed for restore.")
        } else {
            _available.value = true
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                tracker.onAvailable(network)
                Log.d(TAG, "onAvailable(network=$network) networkLost=$networkLost")
                _available.value = true
                if (networkLost) {
                    Log.i(TAG, "Connectivity restored — flushing pending telemetry.")
                    networkLost = false
                    onRestored()
                }
            }

            override fun onLost(network: Network) {
                // Only the LAST network going means offline. Android keeps Wi-Fi and cellular
                // up together through a handover, so treating any single loss as offline
                // reported "You're offline" while Wi-Fi was working fine.
                if (!tracker.onLost(network)) {
                    Log.d(TAG, "onLost(network=$network) — another network is still up.")
                    return
                }
                Log.i(TAG, "Connectivity lost — pending events will sync when online.")
                networkLost = true
                _available.value = false
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.d(TAG, "Network callback registered. activeNetwork=${cm.activeNetwork}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun unregister() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            networkCallback = null
        }
        tracker.clear()
    }

    private companion object {
        private const val TAG = "MicroCoachingSDK"
    }
}
