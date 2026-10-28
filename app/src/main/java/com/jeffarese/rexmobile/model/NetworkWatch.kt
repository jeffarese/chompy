package com.jeffarese.rexmobile.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Tells when the phone's network changes, such as from Wi-Fi to cellular, so connections can be
 * checked at once rather than when their next ping fails.
 *
 * With Tailscale on, the app's network is Tailscale's and stays the same while the phone roams, but
 * its transports follow the network underneath, so a change of those counts too. [onChange] runs
 * on the main thread with what the network is now, such as "cellular".
 */
class NetworkWatch(context: Context, private val onChange: (String) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** The network and its transports, as last seen. Main thread only. */
    private var last: String? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val now = describe(caps)
            val key = "$network $now"
            val before = last
            last = key
            // The first answer is the network the app started on.
            if (before != null && before != key) onChange(now)
        }

        // So the same network coming back counts as a change too.
        override fun onLost(network: Network) {
            if (last?.startsWith("$network ") == true) last = LOST
        }
    }

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(callback, main) }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    companion object {
        private const val LOST = "lost"

        /** "Wi-Fi", "cellular", "Wi-Fi over a VPN", …: the transports, VPN last. */
        fun describe(caps: NetworkCapabilities): String {
            val names = listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
                NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth",
            ).filter { caps.hasTransport(it.first) }.map { it.second }
            val base = names.joinToString(" + ").ifEmpty { "a network" }
            return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "$base over a VPN" else base
        }
    }
}
