package org.olcbox.app.diagnostics

enum class NetworkTransport { UNKNOWN, NONE, WIFI, CELLULAR, ETHERNET, OTHER, MIXED }
data class NetworkSnapshot(
    val transport: NetworkTransport = NetworkTransport.UNKNOWN,
    val connected: Boolean? = null,
    val validated: Boolean? = null,
    val captivePortal: Boolean? = null,
    val metered: Boolean? = null,
    val vpnActive: Boolean? = null,
)

/** Inspect messages locally to extract only a fixed category. Never export their text. */
internal fun networkFailure(error: Throwable?): String? {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (chain.any { it is java.net.UnknownHostException }) return "dns"
    if (chain.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }) return "tls"
    if (chain.any { it is java.net.SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") }) return "timeout"
    val network = chain.filter { it is java.net.SocketException || it is java.net.NoRouteToHostException || it.javaClass.simpleName == "ErrnoException" }
    if (network.isEmpty()) return null
    val text = network.joinToString(" ") { it.message.orEmpty().take(2048).lowercase() }
    return when {
        "econnrefused" in text || "connection refused" in text -> "refused"
        "econnreset" in text || "connection reset" in text -> "reset"
        "enetunreach" in text || "ehostunreach" in text || "unreachable" in text || network.any { it is java.net.NoRouteToHostException } -> "unreachable"
        "etimedout" in text || "timed out" in text -> "timeout"
        "epipe" in text || "broken pipe" in text -> "broken_pipe"
        "econnaborted" in text || "connection abort" in text -> "aborted"
        else -> "socket_other"
    }
}
