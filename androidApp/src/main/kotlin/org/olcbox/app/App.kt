package org.olcbox.app

import android.content.Context

// Наследуем класс приложения ics-openvpn: он готовит каналы уведомлений и слушатель состояния OpenVPN
class App : de.blinkt.openvpn.core.ICSOpenVPNApplication() {
    companion object {
        lateinit var appContext: Context
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // AKULA: вшитое подключение и пробуждение сервера (один раз на версию настроек; без сети — быстро)
        org.olcbox.app.akula.AkulaPreset.seed(applicationContext)
    }
}
