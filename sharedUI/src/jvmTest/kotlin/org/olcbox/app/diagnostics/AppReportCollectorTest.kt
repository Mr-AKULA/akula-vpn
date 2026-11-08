package org.olcbox.app.diagnostics

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AppReportCollectorTest {
    @Test fun persistsPrivateSafeReportsAndRetriesDelivery() {
        var response = 503
        val delivered = mutableListOf<String>()
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                    private val bytes = ByteArrayOutputStream()
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getOutputStream() = bytes
                    override fun getResponseCode(): Int {
                        assertEquals("https://akula-hosting.ru/api/app-reports", url.toString())
                        delivered += bytes.toString("UTF-8")
                        return response
                    }
                }
            }
        }
        val root = createTempDirectory("akula-diagnostics-test").toFile()
        var chained = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chained = true }
        AppReportCollector.install(root, "windows", "10.0")
        val error = IllegalStateException("SECRET-KEY https://private.example/config")
        AppDiagnostics.record("test.connection", error)
        AppDiagnostics.record("test.connection", error)
        val queue = root.resolve("app-reports")
        assertEquals(1, queue.listFiles()!!.size)
        val json = queue.listFiles()!!.single().readText()
        assertFalse(json.contains("SECRET"))
        assertFalse(json.contains("private.example"))
        assertTrue(json.contains("IllegalStateException"))
        AppDiagnostics.record("vpn.connection", diagnostic = VpnDiagnostic.timeout(VpnProtocol.AMNEZIA_WG, 15020, 1))
        AppDiagnostics.record("vpn.connection", diagnostic = VpnDiagnostic.timeout(VpnProtocol.AMNEZIA_WG, 15100, 1))
        AppDiagnostics.record("vpn.connection", diagnostic = VpnDiagnostic(VpnProtocol.OPENVPN, VpnStage.AUTHENTICATE, VpnReason.AUTH_REJECTED))
        AppDiagnostics.record("vpn.connection", kotlinx.coroutines.CancellationException("SECRET"))
        assertEquals(3, queue.listFiles()!!.size, "Group duplicates but preserve distinct reasons and ignore cancellation")
        val timeout = queue.listFiles()!!.map { it.readText() }.single { it.contains("handshake_timeout") }
        assertTrue(timeout.contains("\"elapsed_ms\":15020"))
        assertTrue(timeout.contains("\"protocol\":\"amnezia_wg\""))
        assertTrue(timeout.contains("\"frames\":[]"), "A timeout must not invent an exception stack")
        assertTrue(timeout.contains("diagnostic.event"))
        val flush = AppReportCollector.javaClass.getDeclaredMethod("flush").apply { isAccessible = true }
        flush.invoke(AppReportCollector)
        assertEquals(3, queue.listFiles()!!.size, "A failed upload must remain on disk")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        assertTrue(chained, "Preserve the original crash handler")
        assertEquals(4, queue.listFiles()!!.size)
        assertTrue(queue.listFiles()!!.any { it.readText().contains("\"kind\":\"crash\"") })
        response = 202
        flush.invoke(AppReportCollector)
        assertEquals(0, queue.listFiles()!!.size, "Only acknowledged reports leave the queue")
        assertEquals(5, delivered.size)
        AppReportCollector.networkSnapshotProvider = {
            NetworkSnapshot(NetworkTransport.CELLULAR, connected = true, validated = false, metered = true, vpnActive = false)
        }
        val socket = java.net.SocketException("ECONNRESET SECRET https://private.example/key")
        AppDiagnostics.record("subscription.download", socket, VpnDiagnostic(
            VpnProtocol.UNKNOWN, VpnStage.SUBSCRIPTION_BODY, VpnReason.DOWNLOAD_FAILED,
            elapsedMs = 700, httpStatus = 200, proxyEnabled = false, insecureTls = false,
        ))
        val detailed = queue.listFiles()!!.single().readText()
        assertTrue(detailed.contains("\"network_error\":\"reset\""))
        assertTrue(detailed.contains("\"transport\":\"cellular\""))
        assertTrue(detailed.contains("\"validated\":false"))
        assertTrue(detailed.contains("\"stage\":\"subscription_body\""))
        assertTrue(detailed.contains("\"http_status\":200"))
        assertFalse(detailed.contains("SECRET"))
        assertFalse(detailed.contains("private.example"))
        AppReportCollector.networkSnapshotProvider = { error("provider unavailable") }
        AppDiagnostics.record("test.increased-wait", diagnostic = VpnDiagnostic.timeout(VpnProtocol.AMNEZIA_WG, 30050, 2)
            .copy(timeoutMs = 30000, autoFailover = true, profileCount = 3))
        val increased = queue.listFiles()!!.map { it.readText() }.single { it.contains("\"timeout_ms\"") }
        assertTrue(increased.contains("\"timeout_ms\":30000"))
        assertTrue(increased.contains("\"profile_count\":3"))
        assertFalse(increased.contains("\"network\""), "Provider failure must not prevent recording")
        flush.invoke(AppReportCollector)
        assertEquals(0, queue.listFiles()!!.size)
    }
}
