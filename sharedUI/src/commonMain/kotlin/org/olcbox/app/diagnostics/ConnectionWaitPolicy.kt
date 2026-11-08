package org.olcbox.app.diagnostics

/** A slow network gets time to complete its handshake; failover remains bounded. */
object ConnectionWaitPolicy {
    fun timeoutMs(protocol: VpnProtocol, patient: Boolean): Long = when {
        protocol == VpnProtocol.AMNEZIA_WG && patient -> 30_000L
        protocol == VpnProtocol.AMNEZIA_WG -> 20_000L
        patient -> 45_000L
        else -> 30_000L
    }
}
