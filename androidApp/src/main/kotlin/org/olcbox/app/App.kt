package org.olcbox.app

import android.content.Context

// Наследуем класс приложения ics-openvpn: он готовит каналы уведомлений и слушатель состояния OpenVPN
class App : de.blinkt.openvpn.core.ICSOpenVPNApplication() {
    companion object {
        lateinit var appContext: Context
    }

    override fun onCreate() {
        org.olcbox.app.diagnostics.AppReportCollector.networkSnapshotProvider = {
            org.olcbox.app.diagnostics.AndroidNetworkSnapshot.read(applicationContext)
        }
        org.olcbox.app.diagnostics.AppReportCollector.install(
            noBackupFilesDir, "android", android.os.Build.VERSION.RELEASE
        )
        super.onCreate()
        appContext = applicationContext
        reportPreviousSystemExit()
        // AKULA: вшитое подключение и пробуждение сервера (один раз на версию настроек; без сети — быстро)
        org.olcbox.app.akula.AkulaPreset.seed(applicationContext)
    }

    private fun reportPreviousSystemExit() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val preferences = getSharedPreferences("diagnostics-exits", Context.MODE_PRIVATE)
            val previous = preferences.getLong("checked-at", 0L)
            val now = System.currentTimeMillis()
            if (previous > 0) {
                val manager = getSystemService(android.app.ActivityManager::class.java)
                manager.getHistoricalProcessExitReasons(packageName, 0, 16)
                    .filter { it.timestamp > previous && it.timestamp <= now }
                    .forEach { exit ->
                        val reason = when (exit.reason) {
                            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
                            android.app.ApplicationExitInfo.REASON_ANR -> "anr"
                            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
                            else -> null
                        }
                        if (reason != null) org.olcbox.app.diagnostics.AppReportCollector.recordSystemExit(reason, exit.timestamp)
                    }
            }
            preferences.edit().putLong("checked-at", now).apply()
        } catch (_: Exception) { }
    }
}
