package org.olcbox.app.vpn

import android.content.Context
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.io.BufferedReader
import java.io.StringReader

/**
 * AmneziaWG и WireGuard в AKULA VPN — профили из консоли сервера AKULA (файл .conf или QR-код).
 *
 * Туннель поднимает библиотека AmneziaWG (org.amnezia.awg, собирается в tools/awg-tunnel — Linux-контейнер);
 * olcrtc (режим белых списков) остаётся своей службой OlcboxVpnService. Статус и журнал — общие
 * (OlcboxVpnState), поэтому экран приложения одинаково показывает оба вида подключений.
 * Профиль WireGuard — тот же формат без «мусорных» параметров, его поднимает та же библиотека.
 */
object AwgTunnel {
    private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName() = "akula"
        override fun onStateChange(newState: Tunnel.State) {
            OlcboxVpnState.setStatus(if (newState == Tunnel.State.UP) VpnStatus.Connected else VpnStatus.Disconnected)
        }
    }

    /** Похоже ли на профиль AmneziaWG/WireGuard: секции [Interface] и [Peer]. */
    fun isAwgConfig(text: String): Boolean = text.contains("[Interface]") && text.contains("[Peer]")

    /** Блокирующий вызов — только не из главного потока. */
    fun up(context: Context, configText: String, apps: TunnelApps = TunnelApps.All) {
        val b = backend ?: GoBackend(context.applicationContext).also { backend = it }
        OlcboxVpnState.setStatus(VpnStatus.Connecting)
        try {
            val config = Config.parse(BufferedReader(StringReader(withApps(configText, apps))))
            OlcboxVpnState.addLog("AmneziaWG: ${apps.describe()}")
            b.setState(tunnel, Tunnel.State.UP, config)
            OlcboxVpnState.addLog("AmneziaWG: подключено")
        } catch (e: Exception) {
            OlcboxVpnState.addLog("AmneziaWG: не подключилось — ${e.message}")
            OlcboxVpnState.setStatus(VpnStatus.Error(e.message ?: "не подключилось"))
        }
    }

    /** Список приложений — ключами конфига WireGuard (IncludedApplications / ExcludedApplications) в [Interface]. */
    private fun withApps(configText: String, apps: TunnelApps): String {
        val clean = configText.lines().filterNot {
            it.trim().startsWith("IncludedApplications", ignoreCase = true) ||
                it.trim().startsWith("ExcludedApplications", ignoreCase = true)
        }.joinToString("\n")
        val line = when (apps) {
            TunnelApps.All -> return clean
            is TunnelApps.Only -> "IncludedApplications = " + apps.packages.joinToString(", ")
            is TunnelApps.Except -> "ExcludedApplications = " + apps.packages.joinToString(", ")
        }
        return clean.replaceFirst(Regex("""(?m)^\s*\[Interface]\s*$"""), "[Interface]\n$line")
    }

    fun down() {
        val b = backend ?: return
        try {
            b.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            OlcboxVpnState.addLog("AmneziaWG: ошибка при отключении — ${e.message}")
        }
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
    }

    fun isUp(): Boolean = backend?.getState(tunnel) == Tunnel.State.UP

    /** Сервер ответил: WireGuard «поднимается» сразу, даже если сервер недоступен, — смотрим рукопожатие. */
    fun hasHandshake(): Boolean {
        val stats = runCatching { backend?.getStatistics(tunnel) }.getOrNull() ?: return false
        return stats.peers().any { (stats.peer(it)?.latestHandshakeEpochMillis ?: 0L) > 0L }
    }
}
