package org.olcbox.app.akula

import com.russhwolf.settings.Settings

/**
 * AKULA: своя почта пользователя на Яндексе — ОТКУДА приложение шлёт письма «olc start/stop <код>».
 * Куда слать (почта сервера) и код приходят в подписке вместе с подключением «Белые списки» —
 * пароль сервера в подписку не попадает. Почты нет — «Подключить» открывает почтовую программу
 * с готовым письмом (как страница включения), «Отключить» — сервер сам уснёт без трафика.
 *
 * Только Яндекс: при «белых списках» мобильного интернета из почт работает только она.
 */
data class AkulaMailAccount(val email: String = "", val password: String = "") {
    fun ok(): Boolean = email.contains('@') && password.isNotBlank()

    fun normalized(): AkulaMailAccount = AkulaMailAccount(email.trim(), password.trim().replace(" ", ""))
}

object AkulaMailAccountStore {
    private const val EMAIL = "akula_mail_email"
    private const val PASSWORD = "akula_mail_password"

    private val settings: Settings by lazy { Settings() }

    fun load(): AkulaMailAccount = runCatching {
        AkulaMailAccount(settings.getString(EMAIL, ""), settings.getString(PASSWORD, ""))
    }.getOrDefault(AkulaMailAccount())

    fun save(account: AkulaMailAccount) {
        val a = account.normalized()
        if (a.email.isEmpty() && a.password.isEmpty()) {
            settings.remove(EMAIL)
            settings.remove(PASSWORD)
        } else {
            settings.putString(EMAIL, a.email)
            settings.putString(PASSWORD, a.password)
        }
    }

    /** Письмо в почтовой программе (mailto:), если своя почта не указана */
    fun mailto(to: String, action: String, code: String): String =
        "mailto:$to?subject=olc%20$action%20$code"
}
