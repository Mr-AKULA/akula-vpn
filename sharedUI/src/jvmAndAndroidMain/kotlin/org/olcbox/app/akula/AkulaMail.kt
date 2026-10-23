package org.olcbox.app.akula

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * AKULA: письмо «olc start/stop <код>» со своей почты пользователя (Яндекс, SMTP) на почту сервера из подписки.
 * Привратник сервера читает свой ящик (и «Спам») и включает звонок; «стоп» он выполнит, когда в звонке
 * никого не останется.
 */
object AkulaMail {
    private const val HOST = "smtp.yandex.ru"
    private const val PORT = 465

    /** true — письмо ушло. С повторами: связь рвётся; неверный пароль не повторяем */
    fun command(
        account: AkulaMailAccount,
        to: String,
        code: String,
        action: String,
        protect: (Socket) -> Unit = {},
        log: (String) -> Unit = {},
        attempts: Int = 4
    ): Boolean {
        val a = account.normalized()
        val subject = "olc $action ${code.trim()}"
        var last = ""
        for (attempt in 0 until attempts) {
            try {
                send(a.email, a.password, to.trim(), subject, protect)
                log("AKULA: письмо «$action» отправлено на почту сервера")
                return true
            } catch (e: Exception) {
                last = e.message ?: e.toString()
                if (last.startsWith("AUTH") || attempt == attempts - 1) break
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        log("AKULA: не удалось отправить «$action»: $last")
        return false
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun send(login: String, password: String, to: String, subject: String, protect: (Socket) -> Unit) {
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
            // Ответ SMTP: «250-…» — продолжение, «250 …» — последняя строка
            fun reply(): String {
                while (true) {
                    val line = reader.readLine() ?: throw IllegalStateException("почта закрыла соединение")
                    if (line.length < 4 || line[3] != '-') return line
                }
            }
            fun expect(code: String, what: String) {
                val r = reply()
                if (!r.startsWith(code)) throw IllegalStateException("$what: $r")
            }
            fun b64(v: String) = Base64.encode(v.toByteArray(Charsets.UTF_8))

            expect("220", "почта не ответила")
            send("EHLO akula-vpn")
            expect("250", "почта не ответила")
            send("AUTH LOGIN")
            expect("334", "почта не ответила")
            send(b64(login))
            expect("334", "почта не ответила")
            send(b64(password))
            if (!reply().startsWith("235")) throw IllegalStateException("AUTH: почта не пустила — проверьте пароль приложения")
            send("MAIL FROM:<$login>")
            expect("250", "почта не приняла отправителя")
            send("RCPT TO:<$to>")
            expect("250", "почта не приняла адрес сервера")
            send("DATA")
            expect("354", "почта не приняла письмо")
            send("From: <$login>")
            send("To: <$to>")
            send("Subject: $subject")
            send("Content-Type: text/plain; charset=utf-8")
            send("")
            send("AKULA VPN")
            send(".")
            expect("250", "почта не приняла письмо")
            runCatching { send("QUIT") }
        }
    }
}
