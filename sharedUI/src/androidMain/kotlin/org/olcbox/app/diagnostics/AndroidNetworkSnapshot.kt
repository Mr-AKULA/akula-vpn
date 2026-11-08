package org.olcbox.app.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Only coarse connectivity flags. No network names, addresses, operators or identifiers. */
object AndroidNetworkSnapshot {
    fun read(context: Context): NetworkSnapshot {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val caps = active?.let(manager::getNetworkCapabilities)
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val physical = if (vpn) manager.allNetworks.take(16).mapNotNull(manager::getNetworkCapabilities)
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            else listOfNotNull(caps)
        val transports = physical.map { candidate -> when {
            candidate.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            candidate.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            candidate.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        } }.distinct()
        return NetworkSnapshot(
            transport = when {
                active == null -> NetworkTransport.NONE
                transports.size > 1 -> NetworkTransport.MIXED
                else -> transports.singleOrNull() ?: NetworkTransport.UNKNOWN
            },
            connected = active != null,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            metered = if (active != null) manager.isActiveNetworkMetered else null,
            vpnActive = if (caps != null) vpn else null,
        )
    }
}
