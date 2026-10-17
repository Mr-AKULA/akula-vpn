package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * AmneziaWG / WireGuard в AKULA VPN для Windows.
 *
 * Всё внутри программы (Павел 17.10.2026: «в одном приложении, как на телефоне»): официальный amneziawg.exe
 * (MIT, подписан Privacy Technologies OU) и wintun.dll идут с программой, отдельная AmneziaWG не ставится;
 * запускаются из защищённой папки с проверкой подписи (WindowsVpnTools).
 * Туннель — служба `amneziawg.exe /installtunnelservice <файл>`: маршруты, DNS и защита от утечек — её забота;
 * драйвер Wintun Windows подключает сама и убирает вместе с туннелем.
 * Служба — с ручным запуском: после перезагрузки VPN не поднимается сам, пока приложение показывает «выключено».
 * Нужны права администратора — их запрашивает DesktopVpnManager (перезапуск с UAC).
 */
class WindowsAwgController(private val log: (String) -> Unit) {
    private val config: Path get() = DesktopPaths.appDataDir().resolve("tunnels").resolve("$TUNNEL.conf")

    suspend fun up(configText: String) = withContext(Dispatchers.IO) {
        down()
        val exe = WindowsVpnTools.path("awg/amneziawg.exe")
        Files.createDirectories(config.parent)
        Files.writeString(config, configText)
        val code = run(exe.toString(), "/installtunnelservice", config.toString())
        if (code != 0) error("AmneziaWG не запустил туннель (код $code)")
        run("sc.exe", "config", SERVICE, "start=", "demand")
        repeat(30) {
            if (isUp()) { log("AmneziaWG: туннель запущен"); return@withContext }
            delay(500)
        }
        error("AmneziaWG: служба туннеля не запустилась за 15 секунд")
    }

    suspend fun down() = withContext(Dispatchers.IO) {
        // Остановка средствами Windows (sc), без amneziawg.exe: тот без службы показывает окно ошибки и ждёт,
        // а служба могла остаться от прошлой версии программы. Остановленная служба сама убирает адаптер и маршруты.
        if (!serviceExists()) return@withContext
        run("sc.exe", "stop", SERVICE)
        for (attempt in 1..40) {
            if (!scQuery().second.contains("_PENDING") && !isUp()) break
            delay(250)
        }
        run("sc.exe", "delete", SERVICE)
        repeat(20) {
            if (!serviceExists()) { log("AmneziaWG: туннель остановлен"); return@withContext }
            delay(250)
        }
    }

    // Состояние «RUNNING» в выводе sc не переводится; наличие службы — по коду возврата (1060 — службы нет)
    fun isUp(): Boolean = scQuery().second.contains("RUNNING")

    private fun serviceExists(): Boolean = scQuery().first == 0

    private fun scQuery(): Pair<Int, String> = runCatching {
        val p = ProcessBuilder("sc.exe", "query", SERVICE).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 to out } else p.exitValue() to out
    }.getOrDefault(-1 to "")

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
