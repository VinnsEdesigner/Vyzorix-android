package com.vyzorix.connector.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Watches the device's connectivity and reports each change.
 *
 * The notification says whether the connection is up ("Connection stable and running"
 * versus "No network connection, but still running"), so something has to notice when
 * it changes. Polling would work but wakes the device on a timer for a fact the
 * platform already pushes, so this registers a `NetworkCallback` and reports on
 * `onAvailable`/`onLost`.
 *
 * The "up" test is `NET_CAPABILITY_INTERNET` **validated**, not merely "a network
 * exists": a device joined to a captive-portal Wi-Fi has a network and no reachability,
 * and reporting that as stable would be a lie the user acts on. Validation is the
 * platform's own "this network actually reaches the internet" signal.
 */
class NetworkMonitor(
    private val context: Context,
    /** Called with the new state on every change. */
    private val onChanged: (up: Boolean) -> Unit,
) {
    private val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report()

            override fun onLost(network: Network) = report()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = report()
        }

    /** Starts watching. Idempotent. */
    fun start() {
        val mgr = manager ?: return

        runCatching { mgr.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "could not register the network callback", it) }
    }

    /** Stops watching. Safe to call when not started. */
    fun stop() {
        val mgr = manager ?: return

        runCatching { mgr.unregisterNetworkCallback(callback) }
    }

    private fun report() = onChanged(isUp(context))

    companion object {
        private const val TAG = "NetworkMonitor"

        /** True when the device has a validated internet connection right now. */
        fun isUp(context: Context): Boolean {
            val mgr =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false

            val network = mgr.activeNetwork ?: return false
            val capabilities = mgr.getNetworkCapabilities(network) ?: return false

            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
