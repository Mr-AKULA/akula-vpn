package org.olcbox.app.akula

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * AKULA (Павел 24.10.2026): команда «olc start/stop <код>» кладётся прямо в ящик сервера — IMAP APPEND во «Входящие»,
 * как черновик. Почта и пароль приложения ящика приходят в подписке белых списков.
 *
 * Почему так: под белыми списками мобильного интернета отправка письма (SMTP, приложение Яндекс Почты) не проходит —
 * письма застревают в черновиках, а IMAP Яндекса работает (так годами будит сервер сборка Павла, AkulaWake).
 * Письмо не «отправляется», а сразу лежит в ящике — привратник видит его за секунды, в «Спам» оно не попадает.
 *
 * Сервер 1 — ящик Павла; свои серверы пользователей — ящик владельца сервера (вводит в консоли).
 */
object AkulaServerMailbox {
    private const val HOST = "imap.yandex.ru"
    private const val PORT = 993

    /** true — команда в ящике. С повторами: связь рвётся; неверный пароль не повторяем */
    fun command(
        email: String,
        password: String,
        code: String,
        action: String,
        protect: (Socket) -> Unit = {},
        log: (String) -> Unit = {},
        attempts: Int = 5
    ): Boolean {
        val subject = "olc $action ${code.trim()}"
        var last = ""
        for (attempt in 0 until attempts) {
            try {
                append(email.trim(), password.trim().replace(" ", ""), subject, protect)
                log("AKULA: команда «$action» положена в ящик сервера")
                return true
            } catch (e: Exception) {
                last = e.message ?: e.toString()
                if (last.startsWith("AUTH") || attempt == attempts - 1) break
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        log("AKULA: не удалось положить команду «$action» в ящик сервера: $last")
        return false
    }

    private fun append(login: String, password: String, subject: String, protect: (Socket) -> Unit) {
        val raw = Socket()
        protect(raw)
        raw.connect(InetSocketAddress(HOST, PORT), 15_000)
        raw.soTimeout = 20_000
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, PORT, true) as SSLSocket
        ssl.startHandshake()
        ssl.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val writer = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)
            fun send(line: String) { writer.write(line); writer.write("\r\n"); writer.flush() }
            fun readUntil(tag: String): String {
                while (true) {
                    val line = reader.readLine() ?: throw IllegalStateException("почта закрыла соединение")
                    if (tag.isEmpty() || line.startsWith(tag) || line.startsWith("+")) return line
                }
            }
            fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            readUntil("") // приветствие
            send("a1 LOGIN ${quote(login)} ${quote(password)}")
            if (!readUntil("a1 ").startsWith("a1 OK")) throw IllegalStateException("AUTH: почта сервера не пустила")
            val msg = "From: AKULA VPN <$login>\r\nTo: $login\r\nSubject: $subject\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\nAKULA VPN\r\n"
            val bytes = msg.toByteArray(Charsets.UTF_8)
            send("a2 APPEND INBOX {${bytes.size}}")
            if (!readUntil("a2 ").startsWith("+")) throw IllegalStateException("почта не приняла письмо")
            sock.outputStream.write(bytes); sock.outputStream.write("\r\n".toByteArray()); sock.outputStream.flush()
            if (!readUntil("a2 ").startsWith("a2 OK")) throw IllegalStateException("почта не приняла письмо")
            runCatching { send("a3 LOGOUT") }
        }
    }
}
