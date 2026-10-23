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

/**
 * Где запомнить выбранный язык на ПК (на Android — в его DataStore, см. AndroidVpnManager).
 * Пункт «Язык» в настройках — один и тот же на всех устройствах (Павел 23.10.2026).
 */
object AppLangStore {
    private const val KEY = "app_language"
    private val settings by lazy { com.russhwolf.settings.Settings() }

    fun load(): LangMode = runCatching { LangMode.fromValue(settings.getStringOrNull(KEY)) }.getOrDefault(LangMode.System)

    fun save(mode: LangMode) {
        runCatching { settings.putString(KEY, mode.value) }
    }

    /** Как в системе → русский → English, по нажатию; сразу на всех экранах */
    fun cycle(): LangMode {
        val next = LangMode.entries.let { it[(it.indexOf(AppLang.mode.value) + 1) % it.size] }
        AppLang.mode.value = next
        save(next)
        return next
    }
}

fun LangMode.title(): String = when (this) {
    LangMode.System -> tr("Как в системе", "System default")
    LangMode.Russian -> "Русский"
    LangMode.English -> "English"
}
