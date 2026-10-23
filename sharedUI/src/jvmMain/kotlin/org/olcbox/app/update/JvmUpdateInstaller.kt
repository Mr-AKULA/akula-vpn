package org.olcbox.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.outputStream

class JvmUpdateInstaller(
    private val directory: Path = DesktopPaths.appDataDir().resolve("updates")
) {
    suspend fun downloadAndOpen(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit = {}
    ): Result<String> = runCatching {
        val file = download(asset, onProgress)
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        when {
            desktop?.isSupported(Desktop.Action.OPEN) == true -> desktop.open(file.toFile())
            desktop?.isSupported(Desktop.Action.BROWSE) == true -> desktop.browse(URI(asset.downloadUrl))
            else -> error("No system file handler available for ${asset.name}")
        }
        "Opening ${asset.name}"
    }

    /**
     * AKULA: версия, которую Windows считает установленной (реестр «Программы и компоненты»). Установщик мог уже
     * поставить новую версию, а работает ещё старый процесс — тогда не «Скачать», а «перезапустите».
     */
    suspend fun installedVersion(): String? = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@withContext null
        val roots = listOf(
            """HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall""",
            """HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"""
        )
        val name = Regex("""^\s+DisplayName\s+REG_SZ\s+AKULA VPN\s*$""", RegexOption.MULTILINE)
        val version = Regex("""^\s+DisplayVersion\s+REG_SZ\s+(\S+)""", RegexOption.MULTILINE)
        roots.flatMap { root ->
            runCatching {
                val p = ProcessBuilder("reg.exe", "query", root, "/s").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                // Разделы начинаются со строки HKEY_…; в разделе AKULA VPN берём DisplayVersion
                out.split(Regex("""\r?\n(?=HKEY_)"""))
                    .filter { name.containsMatchIn(it) }
                    .mapNotNull { version.find(it)?.groupValues?.get(1) }
            }.getOrDefault(emptyList())
        }.maxWithOrNull { a, b -> AppUpdateService.compareVersions(a, b) }
    }

    private suspend fun download(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit
    ): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val target = directory.resolve(asset.name.substringAfterLast('/').ifBlank { "olcbox-update" })
        val connection = URL(asset.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes ?: -1L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0L) {
                        reportProgress(
                            (copied.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                            onProgress
                        )
                    }
                }
            }
        }
        reportProgress(1f, onProgress)
        target
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }
}
