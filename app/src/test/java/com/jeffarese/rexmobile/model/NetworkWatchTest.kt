package com.jeffarese.rexmobile.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkWatchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun caps(vararg transports: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { c -> transports.forEach { shadowOf(c).addTransportType(it) } }

    @Test
    fun aChangeOfNetworkOrWhatIsUnderneathIsReported() {
        val changes = mutableListOf<String>()
        val watch = NetworkWatch(context) { changes += it }
        watch.start()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = shadowOf(cm).networkCallbacks.single()
        val wifi: Network = ShadowNetwork.newInstance(1)
        val cellular: Network = ShadowNetwork.newInstance(2)
        val tailscale: Network = ShadowNetwork.newInstance(3)

        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        // The same again, as when the signal changes.
        callback.onCapabilitiesChanged(wifi, caps(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(emptyList<String>(), changes)

        callback.onCapabilitiesChanged(cellular, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals(listOf("cellular"), changes)

        // Tailscale keeps its network while the phone roams underneath it.
        callback.onCapabilitiesChanged(tailscale, caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_CELLULAR))
        callback.onCapabilitiesChanged(tailscale, caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(listOf("cellular", "cellular over a VPN", "Wi-Fi over a VPN"), changes)

        // Lost, then back as it was.
        callback.onLost(tailscale)
        callback.onCapabilitiesChanged(tailscale, caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals("Wi-Fi over a VPN", changes.last())
        assertEquals(4, changes.size)

        watch.stop()
        assertEquals(0, shadowOf(cm).networkCallbacks.size)
    }
}
