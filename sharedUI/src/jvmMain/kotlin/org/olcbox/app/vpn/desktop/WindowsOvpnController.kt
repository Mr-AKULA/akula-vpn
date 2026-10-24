package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.desktop.DesktopPaths
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * OpenVPN в AKULA VPN для Windows (17.10.2026) — внутри программы, без отдельного OpenVPN.
 *
 * openvpn.exe 2.7 (GPL, подписан OpenVPN Inc.) с OpenSSL идёт с программой (запуск — из защищённой папки
 * с проверкой подписи, WindowsVpnTools); туннель — через драйвер
 * ovpn-dco (подписан Microsoft): при первом подключении OpenVPN ставим его в хранилище драйверов (pnputil)
 * и создаём адаптер «AKULA OpenVPN» (tapctl) — сам openvpn.exe 2.7 создаёт адаптеры только через свою службу,
 * которой у нас нет («create_adapter: could not talk to service»). Профили консоли — AES-GCM без сжатия,
 * это и нужно ovpn-dco.
 * Остановка — командой по каналу управления (127.0.0.1, пароль в файле): openvpn сам снимает маршруты и DNS.
 * Нужны права администратора — их запрашивает DesktopVpnManager (перезапуск с UAC).
 */
class WindowsOvpnController(
    private val log: (String) -> Unit,
    private val onUnexpectedExit: (String) -> Unit
) {
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    private var managementPort = 0
    private var managementPassword = ""

    private val workDir: Path get() = DesktopPaths.appDataDir().resolve("openvpn")

    val isRunning: Boolean get() = process?.isAlive == true

    suspend fun up(configText: String) = withContext(Dispatchers.IO) {
        down()
        val tap = prepareAdapter()
        Files.createDirectories(workDir)
        val config = workDir.resolve("akula.ovpn")
        Files.writeString(config, configText)
        managementPassword = randomPassword()
        val passwordFile = workDir.resolve("management.pw")
        Files.writeString(passwordFile, managementPassword + "\n")
        managementPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val exe = WindowsVpnTools.path("ovpn/openvpn.exe")
        val ready = CompletableDeferred<Unit>()
        stopping = false
        val p = ProcessBuilder(
            listOf(
                exe.toString(),
                "--config", config.toString(),
                "--management", "127.0.0.1", managementPort.toString(), passwordFile.toString()
            ) + (if (tap) listOf("--disable-dco", "--dev-node", TAP_ADAPTER) else listOf("--dev-node", ADAPTER)) +
                listOf("--verb", "3")
        ).directory(exe.parent.toFile()).redirectErrorStream(true).start()
        process = p

        thread(name = "akula-openvpn-log", isDaemon = true) {
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val text = line.substringAfter(" us=", line).trim()
                    if (IMPORTANT.any { it in line }) log("OpenVPN: ${text.take(240)}")
                    when {
                        "Initialization Sequence Completed" in line -> ready.complete(Unit)
                        "AUTH_FAILED" in line -> ready.completeExceptionally(IllegalStateException("OpenVPN: сервер не принял профиль"))
                        "Exiting due to fatal error" in line ->
                            ready.completeExceptionally(IllegalStateException("OpenVPN: ${text.take(200)}"))
                    }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            ready.completeExceptionally(IllegalStateException("OpenVPN завершился (код $code)"))
            if (!stopping && process === p) {
                process = null
                onUnexpectedExit("OpenVPN отключился (код $code)")
            }
        }

        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { runCatching { ready.await() } }
        if (result == null || result.isFailure) {
            down()
            throw result?.exceptionOrNull() ?: IllegalStateException("OpenVPN не подключился за ${CONNECT_TIMEOUT_MS / 1000} с — сервер недоступен?")
        }
        log("OpenVPN: подключено")
    }

    suspend fun down() = withContext(Dispatchers.IO) {
        val p = process ?: return@withContext
        stopping = true
        // Мягко: openvpn снимает маршруты, DNS и адаптер сам. Не ответил — завершаем принудительно.
        runCatching {
            Socket(InetAddress.getLoopbackAddress(), managementPort).use { s ->
                s.soTimeout = 2_000
                val out = s.getOutputStream().bufferedWriter()
                out.write(managementPassword + "\n"); out.write("signal SIGTERM\n"); out.flush()
                runCatching { s.getInputStream().read(ByteArray(256)) }
            }
        }
        if (!p.waitFor(8, TimeUnit.SECONDS)) {
            log("OpenVPN: не остановился сам — завершаю")
            p.destroyForcibly()
            p.waitFor(3, TimeUnit.SECONDS)
        }
        process = null
        log("OpenVPN: отключено")
    }

    /**
     * Адаптер для туннеля; true — запасной tap-windows6. Основной — ovpn-dco. У части ноутбуков Windows не создаёт
     * его адаптер («tap_create_adapter», 24.10.2026, причину с ноутбука не узнать): тогда наш пакет драйвера ставится
     * заново поверх (в хранилище мог лежать чужой ovpn-dco от другого VPN), а не помогло — классический TAP.
     * Выбор запоминается до закрытия программы, чтобы не ставить драйвер при каждом подключении.
     */
    private fun prepareAdapter(): Boolean {
        if (!useTap) {
            val first = runCatching { ensureDriver(force = false); ensureAdapter(DCO_HWID, ADAPTER) }
            if (first.isSuccess) return false
            log("OpenVPN: адаптер ovpn-dco не создан — ставлю драйвер заново. ${first.exceptionOrNull()?.message}")
            val second = runCatching { ensureDriver(force = true); ensureAdapter(DCO_HWID, ADAPTER) }
            if (second.isSuccess) return false
            log("OpenVPN: ovpn-dco не работает на этом ПК — перехожу на драйвер TAP. ${second.exceptionOrNull()?.message}")
            useTap = true
        }
        ensureTapDriver()
        ensureAdapter(TAP_HWID, TAP_ADAPTER)
        return true
    }

    /** Драйвер ovpn-dco — один раз в хранилище драйверов Windows; сборка под Windows 10 и 11 своя. */
    private fun ensureDriver(force: Boolean) {
        if (!force && driverInStore("ovpn-dco.inf")) return
        val folder = if (windowsBuild() >= 22000) "win11" else "win10"
        addDriver(WindowsVpnTools.path("ovpn/dco/$folder/ovpn-dco.inf"))
    }

    /** tap-windows6 (root\tap0901): если такой уже стоит (OpenVPN GUI и др.) — подойдёт и он. */
    private fun ensureTapDriver() {
        if (driverInStore("TAP-Windows Provider V9")) return
        addDriver(WindowsVpnTools.path("ovpn/tap/OemVista.inf"))
    }

    private fun driverInStore(name: String) = command("pnputil.exe", "/enum-drivers").second.contains(name, ignoreCase = true)

    private fun addDriver(inf: Path) {
        log("OpenVPN: ставлю драйвер ${inf.fileName}")
        val (code, out) = command("pnputil.exe", "/add-driver", inf.toString(), "/install")
        // 0 — поставлен, 259 — уже был; 3010 — нужна перезагрузка (для нового адаптера обычно не нужна)
        if (code !in setOf(0, 259, 3010)) error("Драйвер OpenVPN не установился (код $code): ${oneLine(out)}")
    }

    /** Адаптер создаётся один раз и остаётся (как у OpenVPN); выключен, пока туннеля нет. */
    private fun ensureAdapter(hwid: String, name: String) {
        val tapctl = WindowsVpnTools.path("ovpn/tapctl.exe").toString()
        if (command(tapctl, "list", "--hwid", hwid).second.contains(name)) return
        log("OpenVPN: создаю адаптер «$name»")
        val (code, out) = command(tapctl, "create", "--hwid", hwid, "--name", name)
        if (code != 0) {
            log("OpenVPN: tapctl create ($hwid) — код $code: ${oneLine(out)}")
            error("Адаптер OpenVPN не создан (код $code): ${oneLine(out).take(200)}")
        }
    }

    /** Вывод tapctl/pnputil — строки через « / »: в окне программы видна вся причина, а не первая строка. */
    private fun oneLine(out: String) = out.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")

    private fun windowsBuild(): Int = command(
        "reg.exe", "query", "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "/v", "CurrentBuildNumber"
    ).second.let { Regex("""CurrentBuildNumber\s+REG_SZ\s+(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

    private fun command(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to out }
        return p.exitValue() to out
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 40_000L
        const val ADAPTER = "AKULA OpenVPN"
        const val TAP_ADAPTER = "AKULA OpenVPN TAP"
        private const val DCO_HWID = "ovpn-dco"
        private const val TAP_HWID = "root\\tap0901"

        /** Для проверки запасного пути на виртуалке: AKULA_OVPN_TAP=1 — сразу TAP. */
        @Volatile private var useTap = System.getenv("AKULA_OVPN_TAP") == "1"
        private val IMPORTANT = listOf(
            "Initialization Sequence Completed", "AUTH_FAILED", "ERROR", "error", "fatal", "DCO", "dco",
            "TLS Error", "Peer Connection Initiated", "Restart pause", "SIGTERM", "Exiting", "WARNING"
        )
    }
}
