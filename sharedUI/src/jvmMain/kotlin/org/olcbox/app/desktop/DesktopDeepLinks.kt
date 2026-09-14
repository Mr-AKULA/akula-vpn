package org.olcbox.app.desktop

import java.awt.Desktop
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.olcbox.app.data.importer.ImportDeepLink

/** Delivers OS launches to one desktop process, even while its window is in the tray. */
class DesktopDeepLinks private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val endpoint: Path,
    private val server: ServerSocket,
    private val token: String
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val incoming = Channel<String>(Channel.UNLIMITED)
    val events = incoming.receiveAsFlow()
    private var handlesMacUrls = false

    private fun listen() {
        thread(name = "olcbox-deep-links", isDaemon = true) {
            while (!closed.get()) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 1_000
                        val input = socket.getInputStream().dataInputStream()
                        if (input.readUTF() != token) return@use
                        val uri = input.readUTF()
                        if (uri.length > ImportDeepLink.MAX_LENGTH) return@use
                        incoming.trySend(uri)
                        socket.getOutputStream().write(1)
                    }
                }
            }
        }
    }

    fun installMacHandler() {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event -> incoming.trySend(event.uri.toString()) }
                handlesMacUrls = true
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (handlesMacUrls) runCatching { Desktop.getDesktop().setOpenURIHandler(null) }
        server.close()
        incoming.close()
        // Remove metadata while holding the lock so a new owner cannot lose its endpoint.
        runCatching { Files.deleteIfExists(endpoint) }
        lock.release()
        channel.close()
    }

    companion object {
        private val loopback = InetAddress.getByName("127.0.0.1")

        /** null means the existing app accepted the launch; this process should exit. */
        fun open(
            args: Array<String>,
            directory: Path = DesktopPaths.appDataDir(),
            waitForPreviousExit: Boolean = false
        ): DesktopDeepLinks? {
            Files.createDirectories(directory)
            val channel = FileChannel.open(
                directory.resolve("deep-links.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE
            )
            val endpoint = directory.resolve("deep-links.endpoint")
            val argument = args.firstOrNull { it.startsWith("olcbox:", ignoreCase = true) }.orEmpty()
            val uri = if (argument.length <= ImportDeepLink.MAX_LENGTH) argument else "olcbox://invalid"
            val deadline = System.nanoTime() + 5_000_000_000L
            try {
                do {
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock != null) {
                        val server = ServerSocket()
                        try {
                            server.bind(InetSocketAddress(loopback, 0))
                            val token = UUID.randomUUID().toString()
                            Files.writeString(endpoint, "${server.localPort}\n$token")
                            if (Files.getFileStore(endpoint).supportsFileAttributeView("posix")) {
                                Files.setPosixFilePermissions(endpoint, PosixFilePermissions.fromString("rw-------"))
                            }
                            return DesktopDeepLinks(channel, lock, endpoint, server, token).also {
                                it.listen()
                                if (uri.isNotEmpty()) it.incoming.trySend(uri)
                            }
                        } catch (error: Exception) {
                            server.close()
                            lock.release()
                            throw error
                        }
                    }
                    // UAC starts the elevated replacement just before the old process exits.
                    if (!waitForPreviousExit && forward(endpoint, uri)) {
                        channel.close()
                        return null
                    }
                    Thread.sleep(50)
                } while (System.nanoTime() < deadline)
                error("Could not deliver the launch to the running Olcbox app")
            } catch (error: Exception) {
                channel.close()
                throw error
            }
        }

        private fun forward(endpoint: Path, uri: String): Boolean = runCatching {
            val metadata = Files.readAllLines(endpoint)
            val port = metadata[0].toInt()
            val token = metadata[1]
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, port), 500)
                socket.soTimeout = 1_000
                val output = socket.getOutputStream().dataOutputStream()
                output.writeUTF(token)
                output.writeUTF(uri)
                output.flush()
                socket.getInputStream().read() == 1
            }
        }.getOrDefault(false)

        private fun java.io.InputStream.dataInputStream() = java.io.DataInputStream(this)
        private fun java.io.OutputStream.dataOutputStream() = java.io.DataOutputStream(this)
    }
}

/** Packaged Windows/Linux apps register the current launcher for this user on first run. */
object DesktopDeepLinkRegistration {
    fun register() {
        if (DesktopPaths.os != DesktopOs.Windows && DesktopPaths.os != DesktopOs.Linux) return
        val executable = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }
            ?: ProcessHandle.current().info().command().orElse(null)
            ?: return
        val path = Path.of(executable).toAbsolutePath()
        // A Gradle/IDE JVM is not a usable application launcher.
        if (System.getenv("APPIMAGE").isNullOrBlank() &&
            !path.fileName.toString().equals("Olcbox", ignoreCase = true) &&
            !path.fileName.toString().equals("Olcbox.exe", ignoreCase = true)) return
        runCatching {
            when (DesktopPaths.os) {
                DesktopOs.Windows -> {
                    val key = "HKCU\\Software\\Classes\\olcbox"
                    command("reg.exe", "add", key, "/ve", "/d", "URL:Olcbox subscription", "/f")
                    command("reg.exe", "add", key, "/v", "URL Protocol", "/d", "", "/f")
                    command("reg.exe", "add", "$key\\shell\\open\\command", "/ve", "/d", "\"$path\" \"%1\"", "/f")
                }
                DesktopOs.Linux -> {
                    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith('/') }
                        ?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".local", "share")
                    val applications = dataHome.resolve("applications")
                    Files.createDirectories(applications)
                    val id = "org.olcbox.app.desktopApp.desktop"
                    Files.writeString(applications.resolve(id), linuxDesktopEntry(path.toString()))
                    command("xdg-mime", "default", id, "x-scheme-handler/olcbox")
                }
                else -> Unit
            }
        }.onFailure { System.err.println("Could not register olcbox:// links for this user") }
    }

    internal fun linuxDesktopEntry(executable: String): String {
        require(executable.none { it == '\n' || it == '\r' || it == '\u0000' })
        val quoted = buildString {
            append('"')
            executable.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\\\\\")
                    '"', '$', '`' -> { append("\\\\"); append(char) }
                    '%' -> append("%%")
                    else -> append(char)
                }
            }
            append('"')
        }
        return """
            [Desktop Entry]
            Type=Application
            Name=Olcbox
            Exec=$quoted %u
            Icon=olcbox
            Categories=Network;Utility;
            Terminal=false
            MimeType=x-scheme-handler/olcbox;
        """.trimIndent() + "\n"
    }

    private fun command(vararg args: String) {
        val process = ProcessBuilder(*args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Protocol registration timed out")
        }
        check(process.exitValue() == 0) { "Protocol registration failed" }
    }
}
