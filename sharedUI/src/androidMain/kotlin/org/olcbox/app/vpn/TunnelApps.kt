package org.olcbox.app.vpn

import android.content.Context
import org.olcbox.app.ui.i18n.tr

/**
 * Раздельный туннель для AmneziaWG/WireGuard и OpenVPN (Павел 17.10.2026: «VPN только для нужных приложений —
 * не работает»). Настройка в приложении была, но её читала только служба olcRTC — наши туннели шли целиком.
 * Логика та же, что у olcRTC (OlcboxVpnService.applySplitTunneling): «только выбранные» с пустым списком — ошибка,
 * «кроме выбранных» с пустым списком — всё через VPN.
 */
sealed class TunnelApps {
    data object All : TunnelApps()
    data class Only(val packages: List<String>) : TunnelApps()
    data class Except(val packages: List<String>) : TunnelApps()

    companion object {
        fun from(context: Context, settings: AndroidSplitTunnelSettings): TunnelApps {
            val own = context.packageName
            // Удалённое приложение Android отвергает целиком — туннель не поднялся бы вовсе
            fun installed(packages: Set<String>) = packages
                .map { it.trim() }
                .filter { it.isNotBlank() && it != own }
                .distinct()
                .filter { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
            return when (settings.mode) {
                AndroidSplitTunnelMode.AllApps -> All
                AndroidSplitTunnelMode.ProxySelected -> installed(settings.proxyPackages).let {
                    if (it.isEmpty()) error(tr(
                        "Выберите приложения, которые пойдут через VPN",
                        "Select apps that should use the VPN"
                    )) else Only(it)
                }
                AndroidSplitTunnelMode.BypassSelected -> installed(settings.bypassPackages).let {
                    if (it.isEmpty()) All else Except(it)
                }
            }
        }
    }

    fun describe(): String = when (this) {
        All -> tr("через VPN — все приложения", "all apps use the VPN")
        is Only -> tr("через VPN — только выбранные (${packages.size})", "only selected apps (${packages.size}) use the VPN")
        is Except -> tr("мимо VPN — выбранные (${packages.size})", "selected apps (${packages.size}) bypass the VPN")
    }
}
