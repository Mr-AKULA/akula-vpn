package org.olcbox.app.diagnostics

import kotlin.test.*
import org.olcbox.app.vpn.VpnStatus

class VpnDiagnosticTest {
    @Test fun distinguishesHandshakeFromConnectionTimeout() {
        val wg = VpnDiagnostic.timeout(VpnProtocol.AMNEZIA_WG, 15001, 2)
        assertEquals(VpnStage.WAIT_HANDSHAKE, wg.stage)
        assertEquals(VpnReason.HANDSHAKE_TIMEOUT, wg.reason)
        assertEquals(15001L, wg.elapsedMs)
        assertEquals(2, wg.attempt)
        for (protocol in listOf(VpnProtocol.OPENVPN, VpnProtocol.OLCRTC)) {
            val timeout = VpnDiagnostic.timeout(protocol, 30001, 1)
            assertEquals(VpnStage.WAIT_CONNECTION, timeout.stage)
            assertEquals(VpnReason.CONNECTION_TIMEOUT, timeout.reason)
        }
    }

    @Test fun errorRetainsOriginalExceptionAndTypedFailure() {
        val cause = IllegalArgumentException("private profile and key must stay local")
        val context = VpnDiagnostic(VpnProtocol.OPENVPN, VpnStage.VALIDATE_PROFILE, VpnReason.PROFILE_INVALID)
        val status = VpnStatus.Error("Profile failed", context, cause)
        assertSame(cause, status.error)
        assertEquals(VpnReason.PROFILE_INVALID, status.diagnostic.reason)
        assertEquals(VpnReason.UNKNOWN_STATE, VpnStatus.Error("Unknown").diagnostic.reason)
    }
}
