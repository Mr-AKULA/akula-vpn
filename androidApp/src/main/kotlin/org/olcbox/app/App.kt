package org.olcbox.app

import android.app.Application
import android.content.Context

class App : Application() {
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
