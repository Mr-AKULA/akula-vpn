package org.olcbox.app.ui.i18n

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.intl.Locale

/**
 * Язык интерфейса AKULA VPN (Павел 16.10.2026): по умолчанию — как в системе (русская система → русский,
 * иначе английский), в настройках можно выбрать вручную. Переключается на лету — без перезапуска:
 * tr() читает состояние, и экраны перерисовываются сами.
 */
enum class LangMode(val value: String) {
    System("system"), Russian("ru"), English("en");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: System
    }
}

object AppLang {
    val mode = mutableStateOf(LangMode.System)

    val isRussian: Boolean
        get() = when (mode.value) {
            LangMode.Russian -> true
            LangMode.English -> false
            LangMode.System -> Locale.current.language.lowercase() == "ru"
        }
}

/** Надпись на языке интерфейса: tr("Настройки", "Settings"). */
fun tr(ru: String, en: String): String = if (AppLang.isRussian) ru else en
