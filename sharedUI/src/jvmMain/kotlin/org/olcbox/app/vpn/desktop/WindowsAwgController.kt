package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * AmneziaWG / WireGuard в AKULA VPN для Windows (16.10.2026).
 *
 * Туннель поднимает официальная AmneziaWG для Windows (MIT, подписанный драйвер): AKULA VPN тихо ставит её
 * из встроенного установщика (native/amneziawg-amd64.msi, SHA-256 проверяется при сборке) и запускает туннель
 * её службой — `amneziawg.exe /installtunnelservice <файл>`: маршруты, DNS и защита от утечек — её забота.
 * Проверено вручную на Windows 10 (VMware) против лабораторного сервера vm1.
 * Нужны права администратора — их запрашивает DesktopVpnManager (перезапуск с UAC, как для режима TUN).
 */
class WindowsAwgController(private val log: (String) -> Unit) {
    private val exe: Path = Paths.get(System.getenv("ProgramFiles") ?: "C:\\Program Files", "AmneziaWG", "amneziawg.exe")
    private val config: Path get() = DesktopPaths.appDataDir().resolve("tunnels").resolve("$TUNNEL.conf")

    suspend fun up(configText: String) = withContext(Dispatchers.IO) {
        ensureInstalled()
        if (isUp()) down()
        Files.createDirectories(config.parent)
        Files.writeString(config, configText)
        val code = run(exe.toString(), "/installtunnelservice", config.toString())
        if (code != 0) error("AmneziaWG не запустил туннель (код $code)")
        // Служба туннеля ставится с автозапуском: после перезагрузки VPN поднимался сам, а приложение показывало
        // «выключено» (и при недоступном сервере пропадал интернет). Запуск — только из приложения.
        run("sc.exe", "config", SERVICE, "start=", "demand")
        repeat(30) {
            if (isUp()) { log("AmneziaWG: туннель запущен"); return@withContext }
            delay(500)
        }
        error("AmneziaWG: служба туннеля не запустилась за 15 секунд")
    }

    suspend fun down() = withContext(Dispatchers.IO) {
        if (!Files.exists(exe)) return@withContext
        run(exe.toString(), "/uninstalltunnelservice", TUNNEL)
        repeat(20) {
            if (!isUp()) { log("AmneziaWG: туннель остановлен"); return@withContext }
            delay(250)
        }
    }

    fun isUp(): Boolean {
        val out = runCatching {
            val p = ProcessBuilder("sc.exe", "query", SERVICE).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().also { p.waitFor(5, TimeUnit.SECONDS) }
        }.getOrDefault("")
        return out.contains("RUNNING")
    }

    private fun ensureInstalled() {
        if (Files.exists(exe)) return
        log("AmneziaWG: ставлю компонент (один раз)")
        val msi = DesktopNativeAssets.resolveAmneziaWgMsi()
        val code = run("msiexec.exe", "/i", msi.toString(), "/qn", "/norestart", "DO_NOT_LAUNCH=1")
        if (code != 0 || !Files.exists(exe)) error("AmneziaWG не установился (код $code)")
    }

    private fun run(vararg cmd: String): Int {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 }
        if (out.isNotBlank()) log("AmneziaWG: ${out.trim().take(300)}")
        return p.exitValue()
    }

    companion object {
        private const val TUNNEL = "akula"
        private const val SERVICE = "AmneziaWGTunnel\$$TUNNEL"
    }
}
