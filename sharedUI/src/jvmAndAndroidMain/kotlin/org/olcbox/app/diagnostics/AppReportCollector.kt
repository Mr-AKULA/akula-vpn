package org.olcbox.app.diagnostics

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.olcbox.app.CurrentAppInfo

/** A bounded disk queue. Crash handling never waits for the network. */
object AppReportCollector {
    private const val ENDPOINT = "https://akula-hosting.ru/api/app-reports"
    private val lock = Any()
    private var directory: File? = null
    private var platform = "unknown"
    private var osVersion = "unknown"
    @Volatile var networkSnapshotProvider: (() -> NetworkSnapshot)? = null
    private val recent = mutableMapOf<String, Long>()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "akula-reports").apply { isDaemon = true }
    }

    fun install(root: File, platformName: String, os: String) {
        try { installInternal(root, platformName, os) } catch (_: Throwable) { }
    }

    private fun installInternal(root: File, platformName: String, os: String) {
        synchronized(lock) {
            if (directory != null) return
            val queue = File(root, "app-reports")
            if (!queue.isDirectory && !queue.mkdirs()) return
            directory = queue
            platform = platformName
            osVersion = os.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-_ " }.take(64).ifEmpty { "unknown" }
        }
        AppDiagnostics.receiver = { operation, error, diagnostic -> record("error", operation, error, diagnostic = diagnostic) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record("crash", "uncaught", error) } catch (_: Throwable) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else error.printStackTrace()
        }
        worker.scheduleWithFixedDelay({ try { flush() } catch (_: Throwable) { } }, 5, 60, TimeUnit.SECONDS)
    }

    private fun symbol(value: String): String = value.filter {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._$<>-"
    }.take(180)

    fun recordSystemExit(reason: String, occurredAt: Long) {
        try {
            record("crash", "android.$reason", IllegalStateException().apply { stackTrace = emptyArray() }, occurredAt)
        } catch (_: Throwable) { }
    }

    private fun record(kind: String, operation: String, error: Throwable?, occurredAt: Long = System.currentTimeMillis(), diagnostic: VpnDiagnostic? = null) {
        synchronized(lock) {
            val root = directory ?: return
            val source = error
            val frames = generateSequence(source) { it.cause }.take(5).flatMap { cause ->
                cause.stackTrace.asSequence().take(24).map { frame ->
                    "${symbol(frame.className)}.${symbol(frame.methodName)}:${frame.lineNumber.coerceIn(-1, 999999)}"
                }
            }.take(48).toList()
            val type = source?.let { symbol(it.javaClass.name) } ?: "diagnostic.event"
            val operationName = operation.filter { it.isLetterOrDigit() || it in "._-" }.take(64)
            val networkError = networkFailure(error)
            val signature = "$kind:$operationName:$type:${frames.take(4)}:${diagnostic?.protocol}:${diagnostic?.stage}:${diagnostic?.reason}:$networkError:${diagnostic?.httpStatus}"
            val network = if (kind == "error") runCatching { networkSnapshotProvider?.invoke() }.getOrNull() else null
            val now = System.currentTimeMillis()
            if (kind != "crash" && now - (recent[signature] ?: 0L) < 300000) return
            if (recent.size >= 128) recent.clear()
            recent[signature] = now
            // Reserve ten slots for crashes even during an extended network outage.
            if ((root.listFiles { file -> file.extension == "json" }?.size ?: 0) >= if (kind == "crash") 50 else 40) return
            val id = UUID.randomUUID().toString()
            val json = buildJsonObject {
                put("id", id)
                put("kind", kind)
                put("operation", operationName)
                put("app_version", CurrentAppInfo.value.version)
                put("platform", platform)
                put("os_version", osVersion)
                put("occurred_at_ms", occurredAt)
                put("exception_type", type)
                networkError?.let { put("network_error", it) }
                network?.let { snapshot -> putJsonObject("network") {
                    put("transport", snapshot.transport.name.lowercase())
                    snapshot.connected?.let { put("connected", it) }
                    snapshot.validated?.let { put("validated", it) }
                    snapshot.captivePortal?.let { put("captive_portal", it) }
                    snapshot.metered?.let { put("metered", it) }
                    snapshot.vpnActive?.let { put("vpn_active", it) }
                } }
                if (diagnostic != null) putJsonObject("context") {
                    put("protocol", diagnostic.protocol.name.lowercase())
                    put("stage", diagnostic.stage.name.lowercase())
                    put("reason", diagnostic.reason.name.lowercase())
                    diagnostic.elapsedMs?.let { put("elapsed_ms", it.coerceIn(0, 86_400_000)) }
                    diagnostic.attempt?.let { put("attempt", it.coerceIn(1, 10000)) }
                    diagnostic.exitCode?.let { put("exit_code", it) }
                    diagnostic.timeoutMs?.let { put("timeout_ms", it.coerceIn(0, 86_400_000)) }
                    diagnostic.autoFailover?.let { put("auto_failover", it) }
                    diagnostic.profileCount?.let { put("profile_count", it.coerceIn(1, 10000)) }
                    diagnostic.proxyEnabled?.let { put("proxy_enabled", it) }
                    diagnostic.insecureTls?.let { put("insecure_tls", it) }
                    diagnostic.httpStatus?.let { put("http_status", it.coerceIn(100, 599)) }
                }
                putJsonArray("frames") { frames.forEach { add(it) } }
                putJsonArray("causes") {
                    generateSequence(source?.cause) { it.cause }.take(4).forEach { add(symbol(it.javaClass.name)) }
                }
            }.toString()
            val temporary = File(root, "$id.tmp")
            java.io.FileOutputStream(temporary).use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            temporary.renameTo(File(root, "$id.json"))
        }
    }

    private fun flush() {
        val files = synchronized(lock) {
            directory?.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }?.take(10)
        } ?: return
        for (file in files) {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "AKULA VPN diagnostics/${CurrentAppInfo.value.version}")
                val body = file.readBytes()
                if (body.size > 32768) continue
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                if (connection.responseCode in 200..299) synchronized(lock) { file.delete() }
                else break
            } finally {
                connection.disconnect()
            }
        }
    }
}
