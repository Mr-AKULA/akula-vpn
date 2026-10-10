package org.olcbox.app.akula

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONObject

/**
 * AKULA: сервер спит, пока не нужен. «Старт» кладёт письмо «olc start <код>» в свой ящик Яндекса
 * (IMAP APPEND прямо во «Входящие»: не попадает в «Спам», доходит за секунды, почта Яндекса доступна
 * и при «белых списках»). Сторож на сервере читает ящик и будит сервер. «Стоп» — «olc stop <код>»:
 * звонок общий, сервер засыпает, когда отключились все.
 *
 * Настройки — отдельно от профилей olcbox (их формат не трогаем), по комнате звонка. Вшитые в сборку —
 * assets/akula_preset.json (у каждого своя сборка: Павел, Настя).
 */
data class AkulaWakeSettings(val email: String, val password: String, val code: String) {
    fun ok() = email.isNotBlank() && password.isNotBlank() && code.isNotBlank()
}

object AkulaWake {
    private const val HOST = "imap.yandex.ru"
    private const val PORT = 993
    private const val PREFS = "akula_wake"

    fun settings(context: Context, room: String): AkulaWakeSettings? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(roomKey(room), null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            AkulaWakeSettings(j.optString("email"), j.optString("password"), j.optString("code"))
        }.getOrNull()?.takeIf { it.ok() }
    }

    fun save(context: Context, room: String, s: AkulaWakeSettings) {
        val j = JSONObject().put("email", s.email.trim()).put("password", s.password.trim().replace(" ", ""))
            .put("code", s.code.trim())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(roomKey(room), j.toString()).apply()
    }

    /** Комната из ссылки бывает с адресом сайта и без — сравниваем по последней части */
    private fun roomKey(room: String) = room.trim().trimEnd('/').substringAfterLast('/').lowercase()

    /** «olc start/stop <код>»; true — письмо положено. С повторами: связь рвётся; неверный пароль не повторяем */
    fun command(s: AkulaWakeSettings, action: String, protect: (Socket) -> Boolean, log: (String) -> Unit): Boolean {
        val subject = "olc $action ${s.code.trim()}"
        var last = ""
        for (attempt in 0 until 5) {
            try {
                append(s.email.trim(), s.password.trim().replace(" ", ""), subject, protect)
                log("AKULA: «$action» отправлено сторожу")
                return true
            } catch (e: Exception) {
                last = e.message ?: e.toString()
                if (last.startsWith("AUTH")) break
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        log("AKULA: не удалось отправить «$action»: $last")
        return false
    }

    private fun append(login: String, password: String, subject: String, protect: (Socket) -> Boolean) {
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
            if (!readUntil("a1 ").startsWith("a1 OK")) throw IllegalStateException("AUTH: почта не пустила — проверьте пароль приложения")
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
