package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Модули туннелей Windows в защищённой папке (17.10.2026).
 *
 * Павел: пользователь ставит AKULA VPN куда удобно — в том числе в папку, куда может писать любая его программа.
 * А amneziawg.exe и openvpn.exe работают с правами системы. Поэтому перед подключением (приложение уже с правами
 * администратора) модули копируются в C:/ProgramData/AKULA VPN/vpn — писать туда могут только администраторы и
 * система, — там же проверяются цифровые подписи, и запускаются они только оттуда. Подменённый файл не запустится.
 */
internal object WindowsVpnTools {
    /** Файл → кем подписан (CN сертификата). Драйвер ovpn-dco проверяет сама Windows при установке. */
    private val SIGNED = mapOf(
        "awg/amneziawg.exe" to "Privacy Technologies OU",
        "awg/wintun.dll" to "WireGuard LLC",
        "ovpn/openvpn.exe" to "OpenVPN Inc.",
        "ovpn/tapctl.exe" to "OpenVPN Inc.",
        "ovpn/libcrypto-3-x64.dll" to "OpenVPN Inc.",
        "ovpn/libssl-3-x64.dll" to "OpenVPN Inc.",
        "ovpn/libpkcs11-helper-1.dll" to "OpenVPN Inc.",
        "ovpn/vcruntime140.dll" to "Microsoft Corporation",
    )
    private val DRIVER = listOf("win10", "win11").flatMap { os ->
        listOf("inf", "cat", "sys").map { "ovpn/dco/$os/ovpn-dco.$it" }
    } + listOf("OemVista.inf", "tap0901.cat", "tap0901.sys").map { "ovpn/tap/$it" } // запасной tap-windows6

    val dir: Path = Paths.get(System.getenv("ProgramData") ?: "C:\\ProgramData", "AKULA VPN", "vpn")

    @Volatile private var ready = false

    /** Путь к модулю в защищённой папке; при первом обращении — копирование, права доступа и проверка подписей. */
    fun path(relative: String): Path {
        prepare()
        return dir.resolve(relative)
    }

    @Synchronized
    fun prepare() {
        if (ready) return
        Files.createDirectories(dir)
        // Только система (S-1-5-18) и администраторы (S-1-5-32-544) пишут; пользователи (S-1-5-32-545) — читают.
        // SID, а не имена групп: на русской Windows «Администраторы».
        command(
            "icacls.exe", dir.parent.toString(), "/inheritance:r",
            "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F", "*S-1-5-32-545:(OI)(CI)RX", "/Q"
        ).also { (code, out) -> check(code == 0) { "Не удалось закрыть папку модулей (icacls $code): ${out.trim().take(200)}" } }

        (SIGNED.keys + DRIVER).forEach { relative ->
            val source = DesktopNativeAssets.windowsVpnTool(relative)
            val target = dir.resolve(relative)
            if (Files.exists(target) && sha256(target) == sha256(source)) return@forEach
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
        verifySignatures()
        ready = true
    }

    private fun verifySignatures() {
        val list = SIGNED.keys.joinToString(",") { "'" + dir.resolve(it).toString().replace("'", "''") + "'" }
        val script = "foreach (\$f in @($list)) { \$s = Get-AuthenticodeSignature -LiteralPath \$f; " +
            "Write-Output (\$f + '|' + \$s.Status + '|' + \$s.SignerCertificate.Subject) }"
        val (code, out) = command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
        check(code == 0) { "Не удалось проверить подписи модулей (код $code)" }
        val results = out.lines().filter { it.contains('|') }.associate { line ->
            val parts = line.split('|', limit = 3)
            Paths.get(parts[0].trim()) to (parts.getOrElse(1) { "" }.trim() to parts.getOrElse(2) { "" })
        }
        val bad = SIGNED.filter { (relative, signer) ->
            val (status, subject) = results[dir.resolve(relative)] ?: ("" to "")
            !(status == "Valid" && subject.contains("CN=$signer"))
        }.keys
        if (bad.isNotEmpty()) {
            // Не оставляем подменённое даже в закрытой папке: при следующем подключении скопируется заново
            bad.forEach { runCatching { Files.deleteIfExists(dir.resolve(it)) } }
            error("Модуль ${bad.first()} не прошёл проверку подписи — переустановите AKULA VPN")
        }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun command(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to out }
        return p.exitValue() to out
    }
}
