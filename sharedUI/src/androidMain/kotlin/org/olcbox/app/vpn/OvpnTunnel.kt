package org.olcbox.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus as OvpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.io.StringReader

/**
 * OpenVPN в AKULA VPN — профили .ovpn из консоли сервера AKULA («АнтиЗапрет»: только заблокированное / весь
 * трафик, UDP/TCP). Движок — ics-openvpn (OpenVPN 2, GPL v2+ → приложение под GPL v3; решение Павла 16.10.2026),
 * собирается в tools/ics-openvpn. AmneziaWG — AwgTunnel, olcrtc — OlcboxVpnService; статус у всех общий.
 */
object OvpnTunnel {
    @Volatile private var level: ConnectionStatus = ConnectionStatus.LEVEL_NOTCONNECTED
    @Volatile private var active = false

    private val listener = object : OvpnStatus.StateListener {
        override fun updateState(state: String?, logmessage: String?, localizedResId: Int, lvl: ConnectionStatus?, intent: Intent?) {
            if (!active || lvl == null) return
            level = lvl
            OlcboxVpnState.setStatus(
                when (lvl) {
                    ConnectionStatus.LEVEL_CONNECTED -> VpnStatus.Connected
                    ConnectionStatus.LEVEL_NOTCONNECTED -> VpnStatus.Disconnected
                    ConnectionStatus.LEVEL_AUTH_FAILED -> VpnStatus.Error("Сервер не принял профиль")
                    else -> VpnStatus.Connecting
                }
            )
        }

        override fun setConnectedVPN(uuid: String?) {}
    }

    /** Похоже ли на профиль OpenVPN: строки client/remote и сертификат. */
    fun isOvpnConfig(text: String): Boolean =
        Regex("""(?m)^\s*remote\s+\S+""").containsMatchIn(text) && (text.contains("<ca>") || text.contains("\nca "))

    fun up(context: Context, configText: String, name: String) {
        OvpnStatus.addStateListener(listener)
        active = true
        OlcboxVpnState.setStatus(VpnStatus.Connecting)
        try {
            val parser = ConfigParser()
            parser.parseConfig(StringReader(configText))
            val profile = parser.convertProfile()
            profile.mName = name
            ProfileManager.setTemporaryProfile(context.applicationContext, profile)
            VPNLaunchHelper.startOpenVpn(profile, context.applicationContext, "AKULA VPN", true)
            OlcboxVpnState.addLog("OpenVPN: запускаю «$name»")
        } catch (e: Exception) {
            active = false
            OlcboxVpnState.addLog("OpenVPN: профиль не прочитался — ${e.message}")
            OlcboxVpnState.setStatus(VpnStatus.Error(e.message ?: "профиль не прочитался"))
        }
    }

    /** Остановить через внутренний интерфейс службы OpenVPN (привязка на мгновение). */
    fun down(context: Context) {
        if (!active) return
        val app = context.applicationContext
        val intent = Intent(app, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        app.bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, binder: IBinder?) {
                runCatching { IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false) }
                runCatching { app.unbindService(this) }
            }
            override fun onServiceDisconnected(n: ComponentName?) {}
        }, Context.BIND_AUTO_CREATE)
        active = false
        level = ConnectionStatus.LEVEL_NOTCONNECTED
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
    }

    fun isUp(): Boolean = active
    fun isConnected(): Boolean = active && level == ConnectionStatus.LEVEL_CONNECTED
}
