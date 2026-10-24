package org.olcbox.app.vpn.desktop

import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * AKULA (24.10.2026): журнал ПК-версии ещё и в файл — %APPDATA%\\AKULA VPN\\logs\\akula-vpn.log (до 1 МБ, плюс
 * предыдущий .1). Видно, что было, даже если программа упала или её закрыли; удобно для поддержки и проверок.
 */
internal object DesktopFileLog {
    private const val MAX_BYTES = 1_000_000L
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val file by lazy { DesktopPaths.appDataDir().resolve("logs").resolve("akula-vpn.log") }

    @Synchronized
    fun append(message: String) {
        runCatching {
            Files.createDirectories(file.parent)
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling("akula-vpn.log.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(
                file, "${LocalDateTime.now().format(time)} $message\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        }
    }
}
