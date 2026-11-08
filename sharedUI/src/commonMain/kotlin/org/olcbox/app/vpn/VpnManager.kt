package org.olcbox.app.vpn

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    object Connecting : VpnStatus()
    object Connected : VpnStatus()
    object Reconnecting : VpnStatus()
    object Stopping : VpnStatus()
    data class Error(
        val message: String,
        val diagnostic: org.olcbox.app.diagnostics.VpnDiagnostic = org.olcbox.app.diagnostics.VpnDiagnostic(
            org.olcbox.app.diagnostics.VpnProtocol.UNKNOWN,
            org.olcbox.app.diagnostics.VpnStage.UNKNOWN,
            org.olcbox.app.diagnostics.VpnReason.UNKNOWN_STATE,
        ),
        val error: Throwable? = null,
    ) : VpnStatus() {
        init { org.olcbox.app.diagnostics.AppDiagnostics.record("vpn.connection", error, diagnostic) }
    }
}

interface VpnManager {
    val logs: StateFlow<List<String>>
    val status: StateFlow<VpnStatus>
    val isConnected: StateFlow<Boolean>
    fun needsPermission(): Boolean
    fun startVpn()
    fun stopVpn()
    suspend fun ping(locationConfig: LocationConfig): Long?
    suspend fun checkConnection(locationConfig: LocationConfig): Long?
    fun subscriptionFetchProxy(): SubscriptionFetchProxy? = null
}
