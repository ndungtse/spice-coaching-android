package com.medtroniclabs.microcoaching.sdk.runtime

/**
 * Tracks which networks are currently up, so connectivity is "any network is up" rather than
 * "the last callback said so".
 *
 * `ConnectivityManager.NetworkCallback` reports per **network**, and Android keeps several
 * alive at once during a handover. Treating a lone `onLost` as "offline" is wrong: connecting
 * to Wi-Fi while on cellular fires `onAvailable(wifi)` and then, when the system drops the now
 * redundant cellular link, `onLost(cellular)` — which would flip a single boolean to offline
 * even though Wi-Fi is working. Whichever callback happened to land last decided the answer,
 * which is why the bug looked intermittent and why restarting the app "fixed" it (registration
 * re-primes from `activeNetwork`).
 *
 * Membership-based, so only losing the *last* network means offline.
 *
 * Pure and Android-free so the state machine is unit-testable; callbacks arrive on a binder
 * thread, hence the synchronisation.
 */
internal class NetworkAvailabilityTracker {

    private val upNetworks = LinkedHashSet<Any>()

    /** True while at least one network is up. */
    val isAvailable: Boolean
        get() = synchronized(upNetworks) { upNetworks.isNotEmpty() }

    /** Records [network] as up. Idempotent. @return true if this was the first one up. */
    fun onAvailable(network: Any): Boolean = synchronized(upNetworks) {
        val wasOffline = upNetworks.isEmpty()
        upNetworks.add(network)
        wasOffline
    }

    /**
     * Records [network] as gone. Unknown networks are ignored.
     * @return true only if that was the **last** network — i.e. we are genuinely offline now.
     */
    fun onLost(network: Any): Boolean = synchronized(upNetworks) {
        val wasOnline = upNetworks.isNotEmpty()
        upNetworks.remove(network)
        wasOnline && upNetworks.isEmpty()
    }

    /** Drops all tracked networks (re-registration). */
    fun clear() = synchronized(upNetworks) { upNetworks.clear() }
}
