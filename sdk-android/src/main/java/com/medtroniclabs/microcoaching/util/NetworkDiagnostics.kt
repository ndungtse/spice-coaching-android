package com.medtroniclabs.microcoaching.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Shared `ConnectivityManager` diagnostics.
 *
 * Emits a single logcat line describing the current network state at trigger /
 * schedule / work boundaries so QA can correlate "download didn't start" reports
 * with the active network type, transport flags, and metering status at the moment
 * the SDK saw the request. Previously duplicated verbatim in `ModelManager` and
 * `ModelDownloadWorker`.
 */
internal object NetworkDiagnostics {

    /** Log a one-line snapshot of [context]'s active network under [tag], labelled [stage]. */
    fun logSnapshot(context: Context, tag: String, stage: String) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(tag, "Network snapshot[$stage]: ConnectivityManager unavailable")
            return
        }
        val net = cm.activeNetwork
        if (net == null) {
            Log.w(tag, "Network snapshot[$stage]: activeNetwork=null (no active connection)")
            return
        }
        val caps = cm.getNetworkCapabilities(net)
        if (caps == null) {
            Log.w(tag, "Network snapshot[$stage]: capabilities=null for activeNetwork=${net.networkHandle}")
            return
        }
        Log.i(
            tag,
            "Network snapshot[$stage]: activeNetwork=${net.networkHandle}, " +
                "transports=${transportSummary(caps)}, " +
                "notMetered=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}, " +
                "internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}, " +
                "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}",
        )
    }

    fun transportSummary(caps: NetworkCapabilities): String {
        val flags = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) flags += "CELLULAR"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) flags += "WIFI"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) flags += "ETHERNET"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) flags += "VPN"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) flags += "BLUETOOTH"
        return if (flags.isEmpty()) "[]" else flags.joinToString(prefix = "[", postfix = "]")
    }
}
