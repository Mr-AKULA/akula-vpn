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
        val flush = AppReportCollector.javaClass.getDeclaredMethod("flush").apply { isAccessible = true }
        flush.invoke(AppReportCollector)
        assertEquals(1, queue.listFiles()!!.size, "A failed upload must remain on disk")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
        assertTrue(chained, "Preserve the original crash handler")
        assertEquals(2, queue.listFiles()!!.size)
        assertTrue(queue.listFiles()!!.any { it.readText().contains("\"kind\":\"crash\"") })
        response = 202
        flush.invoke(AppReportCollector)
        assertEquals(0, queue.listFiles()!!.size, "Only acknowledged reports leave the queue")
        assertEquals(3, delivered.size)
    }
}
