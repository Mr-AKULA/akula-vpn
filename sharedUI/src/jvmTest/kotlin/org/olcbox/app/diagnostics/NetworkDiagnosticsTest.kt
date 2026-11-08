package org.olcbox.app.diagnostics

import java.net.*
import javax.net.ssl.SSLHandshakeException
import kotlin.test.*

class NetworkDiagnosticsTest {
    @Test fun classifiesWrappedFailuresWithoutExportingMessages() {
        val cases = listOf(
            UnknownHostException("private.host") to "dns",
            SSLHandshakeException("private certificate") to "tls",
            SocketTimeoutException("private endpoint") to "timeout",
            SocketException("ECONNRESET private endpoint") to "reset",
            SocketException("ECONNREFUSED") to "refused",
            NoRouteToHostException("private host") to "unreachable",
            SocketException("Broken pipe") to "broken_pipe",
            SocketException("ECONNABORTED") to "aborted",
            SocketException("unknown native failure") to "socket_other",
        )
        for ((error, expected) in cases) assertEquals(expected, networkFailure(IllegalStateException("wrapper", error)))
        assertNull(networkFailure(IllegalArgumentException("unrelated")))
        assertNull(networkFailure(null))
    }

    @Test fun increasedWaitRemainsBoundedForEachProtocolAndFailoverMode() {
        assertEquals(30_000L, ConnectionWaitPolicy.timeoutMs(VpnProtocol.AMNEZIA_WG, true))
        assertEquals(20_000L, ConnectionWaitPolicy.timeoutMs(VpnProtocol.AMNEZIA_WG, false))
        for (protocol in listOf(VpnProtocol.OPENVPN, VpnProtocol.OLCRTC)) {
            assertEquals(45_000L, ConnectionWaitPolicy.timeoutMs(protocol, true))
            assertEquals(30_000L, ConnectionWaitPolicy.timeoutMs(protocol, false))
        }
    }
}
