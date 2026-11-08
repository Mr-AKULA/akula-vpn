package org.olcbox.app.diagnostics

/** Closed vocabularies: no endpoint, profile, key, exception message or user identifier. */
enum class VpnProtocol { UNKNOWN, AMNEZIA_WG, OPENVPN, OLCRTC }
enum class VpnStage { UNKNOWN, VALIDATE_PROFILE, START_TUNNEL, WAIT_HANDSHAKE, WAIT_CONNECTION, AUTHENTICATE, START_TRANSPORT, ESTABLISH_TUN, RUNNING, SPLIT_TUNNEL, SUBSCRIPTION_REQUEST, SUBSCRIPTION_BODY }
enum class VpnReason { UNKNOWN_STATE, HANDSHAKE_TIMEOUT, CONNECTION_TIMEOUT, AUTH_REJECTED, PROFILE_INVALID, START_FAILED, TRANSPORT_FAILED, NATIVE_UNAVAILABLE, TUN_FAILED, PERMISSION_REQUIRED, NO_PROFILE, UNSUPPORTED_PLATFORM, SPLIT_APPS_INVALID, PROCESS_EXITED, ALL_ATTEMPTS_FAILED, DOWNLOAD_FAILED, HTTP_ERROR, EMPTY_RESPONSE }

data class VpnDiagnostic(
    val protocol: VpnProtocol,
    val stage: VpnStage,
    val reason: VpnReason,
    val elapsedMs: Long? = null,
    val attempt: Int? = null,
    val exitCode: Int? = null,
    val timeoutMs: Long? = null,
    val autoFailover: Boolean? = null,
    val profileCount: Int? = null,
    val proxyEnabled: Boolean? = null,
    val insecureTls: Boolean? = null,
    val httpStatus: Int? = null,
) {
    companion object {
        fun timeout(protocol: VpnProtocol, elapsedMs: Long, attempt: Int) = VpnDiagnostic(
            protocol,
            if (protocol == VpnProtocol.AMNEZIA_WG) VpnStage.WAIT_HANDSHAKE else VpnStage.WAIT_CONNECTION,
            if (protocol == VpnProtocol.AMNEZIA_WG) VpnReason.HANDSHAKE_TIMEOUT else VpnReason.CONNECTION_TIMEOUT,
            elapsedMs, attempt,
        )
    }
}
