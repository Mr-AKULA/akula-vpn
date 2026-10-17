package org.olcbox.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_AUTO_FAILOVER
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME_INITIALIZED
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.security.SecureRandom

class AndroidVpnManager(private val context: Context) : VpnManager {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connectionMode = MutableStateFlow(AndroidConnectionMode.Tun)
    private val _proxySettings = MutableStateFlow(AndroidSocksProxySettings())
    private val _splitTunnelSettings = MutableStateFlow(AndroidSplitTunnelSettings())
    private val _autoFailoverEnabled = MutableStateFlow(false)  // автопереключение — по умолчанию выключено, включает сам пользователь (Павел 16.10.2026)
    private val _installedApps = MutableStateFlow<List<AndroidInstalledApp>>(emptyList())
    private val deviceIdentityProvider = PersistentDeviceIdentityProvider(
        LocationsDataSourceImpl(appContext)
    )

    override val logs: StateFlow<List<String>> = OlcboxVpnState.logs
    override val status: StateFlow<VpnStatus> = OlcboxVpnState.status
    override val isConnected: StateFlow<Boolean> = OlcboxVpnState.isConnected
    val connectionMode: StateFlow<AndroidConnectionMode> = _connectionMode.asStateFlow()
    val proxySettings: StateFlow<AndroidSocksProxySettings> = _proxySettings.asStateFlow()
    val splitTunnelSettings: StateFlow<AndroidSplitTunnelSettings> = _splitTunnelSettings.asStateFlow()
    val autoFailoverEnabled: StateFlow<Boolean> = _autoFailoverEnabled.asStateFlow()
    val installedApps: StateFlow<List<AndroidInstalledApp>> = _installedApps.asStateFlow()

    init {
        scope.launch {
            ensureProxySettings()
            launch {  // язык интерфейса — из настроек (по умолчанию как в системе)
                appContext.vpnPrefDataStore.data.collect { prefs ->
                    org.olcbox.app.ui.i18n.AppLang.mode.value =
                        org.olcbox.app.ui.i18n.LangMode.fromValue(prefs[org.olcbox.app.vpn.data.KEY_APP_LANGUAGE])
                }
            }
            appContext.vpnPrefDataStore.data
                .map { preferences ->
                    val mode = AndroidConnectionMode.fromValue(preferences[KEY_ANDROID_CONNECTION_MODE])
                    val proxy = AndroidSocksProxySettings(
                        host = AndroidSocksProxySettings.sanitizeHost(
                            preferences[KEY_ANDROID_SOCKS_HOST]
                        ),
                        port = AndroidSocksProxySettings.sanitizePort(
                            preferences[KEY_ANDROID_SOCKS_PORT]
                        ),
                        username = preferences[KEY_ANDROID_SOCKS_USERNAME].orEmpty(),
                        password = preferences[KEY_ANDROID_SOCKS_PASSWORD].orEmpty()
                    )
                    val splitTunnel = AndroidSplitTunnelSettings(
                        mode = AndroidSplitTunnelMode.fromValue(
                            preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE]
                        ),
                        proxyPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS].orEmpty(),
                        bypassPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS].orEmpty()
                    )
                    AndroidAppPreferences(
                        mode = mode,
                        proxy = proxy,
                        splitTunnel = splitTunnel,
                        autoFailoverEnabled = preferences[KEY_ANDROID_AUTO_FAILOVER] == true
                    )
                }
                .collect { settings ->
                    _connectionMode.value = settings.mode
                    _proxySettings.value = settings.proxy
                    _splitTunnelSettings.value = settings.splitTunnel
                    _autoFailoverEnabled.value = settings.autoFailoverEnabled
                }
        }
        refreshInstalledApps()
    }

    override fun needsPermission(): Boolean = needsPermission(_connectionMode.value)

    fun needsPermission(mode: AndroidConnectionMode): Boolean {
        return mode == AndroidConnectionMode.Tun && VpnService.prepare(context) != null
    }

    fun selectConnectionMode(mode: AndroidConnectionMode) {
        _connectionMode.value = mode
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_CONNECTION_MODE] = mode.value
            }
        }
    }

    fun setLanguage(mode: org.olcbox.app.ui.i18n.LangMode) {
        org.olcbox.app.ui.i18n.AppLang.mode.value = mode
        scope.launch {
            appContext.vpnPrefDataStore.edit { it[org.olcbox.app.vpn.data.KEY_APP_LANGUAGE] = mode.value }
        }
    }

    fun setAutoFailoverEnabled(enabled: Boolean) {
        _autoFailoverEnabled.value = enabled
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_AUTO_FAILOVER] = enabled
            }
        }
    }

    fun updateProxySettings(
        host: String,
        username: String,
        password: String,
        port: Int = _proxySettings.value.port
    ) {
        val sanitizedHost = AndroidSocksProxySettings.sanitizeHost(host)
        val sanitizedUsername = username.trim().take(MAX_SOCKS_USERNAME_LENGTH)
            .ifBlank { generateProxyUsername() }
        val sanitized = password.trim().take(MAX_SOCKS_PASSWORD_LENGTH)
            .ifBlank { generateProxyPassword() }
        val sanitizedPort = AndroidSocksProxySettings.sanitizePort(port)
        _proxySettings.value = _proxySettings.value.copy(
            host = sanitizedHost,
            port = sanitizedPort,
            username = sanitizedUsername,
            password = sanitized
        )
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SOCKS_HOST] = sanitizedHost
                preferences[KEY_ANDROID_SOCKS_PORT] = sanitizedPort
                preferences[KEY_ANDROID_SOCKS_USERNAME] = sanitizedUsername
                preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = sanitized
            }
        }
    }

    fun updateProxyPassword(password: String) {
        updateProxySettings(
            host = _proxySettings.value.host,
            username = _proxySettings.value.username,
            password = password
        )
    }

    fun regenerateProxyPassword() {
        updateProxyPassword(generateProxyPassword())
    }

    fun refreshInstalledApps() {
        scope.launch {
            _installedApps.value = loadInstalledApps()
        }
    }

    fun selectSplitTunnelMode(mode: AndroidSplitTunnelMode) {
        _splitTunnelSettings.value = _splitTunnelSettings.value.copy(mode = mode)
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE] = mode.value
            }
        }
        restartTunnelForApps()
    }

    private var appsRestartJob: kotlinx.coroutines.Job? = null

    /** Список приложений поменяли при включённом AmneziaWG/OpenVPN — переподключаемся с новым списком. */
    private fun restartTunnelForApps() {
        if (!AwgTunnel.isUp() && !OvpnTunnel.isUp()) return
        appsRestartJob?.cancel()
        appsRestartJob = scope.launch {
            kotlinx.coroutines.delay(1_500)  // галочки ставят подряд — переподключаемся один раз
            if (!AwgTunnel.isUp() && !OvpnTunnel.isUp()) return@launch
            OlcboxVpnState.addLog(org.olcbox.app.ui.i18n.tr("Список приложений изменился — переподключаю", "App list changed — reconnecting"))
            stopVpn()
            kotlinx.coroutines.delay(1_500)
            startVpn()
        }
    }

    fun toggleSplitTunnelApp(list: AndroidSplitTunnelList, packageName: String) {
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> {
                val packages = current.proxyPackages.toggle(packageName)
                current.copy(proxyPackages = packages)
            }

            AndroidSplitTunnelList.Bypass -> {
                val packages = current.bypassPackages.toggle(packageName)
                current.copy(bypassPackages = packages)
            }
        }

        updateSplitTunnelSettings(next)
    }

    fun setSplitTunnelApps(list: AndroidSplitTunnelList, packages: Set<String>) {
        val normalizedPackages = packages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> current.copy(proxyPackages = normalizedPackages)
            AndroidSplitTunnelList.Bypass -> current.copy(bypassPackages = normalizedPackages)
        }

        updateSplitTunnelSettings(next)
    }

    private var sharedLocations: org.olcbox.app.data.repository.LocationsRepository? = null
    private val ownLocations by lazy {
        org.olcbox.app.data.datasource.LocationsRepositoryImpl(LocationsDataSourceImpl(appContext))
    }
    // Тот же репозиторий, что у экрана: автопереключение меняет выбранное подключение — экран видит сразу
    private val locations get() = sharedLocations ?: ownLocations

    fun attachLocations(repository: org.olcbox.app.data.repository.LocationsRepository) {
        sharedLocations = repository
    }

    /** Активное подключение AmneziaWG/WireGuard — поднимает библиотека AmneziaWG, иначе — служба olcrtc. */
    private var connectJob: kotlinx.coroutines.Job? = null

    /**
     * Сначала — выбранное подключение. Автопереключение (настройка, Павел 16.10.2026): не ответило в срок —
     * пробуем следующие по списку, по кругу один раз. Срок: AmneziaWG/WireGuard — 8 секунд: WireGuard повторяет рукопожатие раз в 5 с, а первое после включения
     * часто теряется — с 5 с отбраковывался и рабочий сервер (эмулятор 16.10.2026);
     * olcrtc (белые списки) — 20 секунд: видеозвонок честно собирается дольше 5 секунд.
     */
    override fun startVpn() {
        connectJob?.cancel()
        connectJob = scope.launch {
            val all = runCatching { locations.getAllLocations() }.getOrDefault(emptyList())
                .filter { it.location.isComplete() }
            val active = runCatching { locations.getActiveLocation() }.getOrNull()
            val order = listOfNotNull(active) + all.filter { it.storageId != active?.storageId }
            if (order.isEmpty()) {
                withContext(Dispatchers.Main) { startOlcrtcVpn() }  // как раньше: служба сама скажет, что выбрать
                return@launch
            }
            for ((i, entry) in order.withIndex()) {
                if (i > 0) {
                    locations.setActiveLocationId(entry.storageId)
                    OlcboxVpnState.addLog("Автопереключение: пробую «${entry.location.displayName()}»")
                }
                startEntry(entry)
                if (!_autoFailoverEnabled.value || order.size == 1) return@launch
                if (waitConnected(entry)) return@launch
                OlcboxVpnState.addLog("«${entry.location.displayName()}» не ответило — следующее")
                stopEntry(entry)
                kotlinx.coroutines.delay(500)
            }
            OlcboxVpnState.setStatus(VpnStatus.Error("Ни одно подключение не ответило"))
        }
    }

    private suspend fun startEntry(entry: org.olcbox.app.data.model.LocationEntry) {
        val awg = entry.awgConfig
        val ovpn = entry.ovpnConfig
        // Раздельный туннель (настройка «Приложения»): раньше его читала только служба olcRTC
        val apps = if (awg.isNullOrBlank() && ovpn.isNullOrBlank()) TunnelApps.All else try {
            TunnelApps.from(appContext, _splitTunnelSettings.value)
        } catch (e: IllegalStateException) {
            OlcboxVpnState.addLog(e.message ?: "")
            OlcboxVpnState.setStatus(VpnStatus.Error(e.message ?: ""))
            return
        }
        when {
            !awg.isNullOrBlank() -> AwgTunnel.up(appContext, awg, apps)
            !ovpn.isNullOrBlank() -> withContext(Dispatchers.Main) { OvpnTunnel.up(appContext, ovpn, entry.location.displayName(), apps) }
            else -> withContext(Dispatchers.Main) { startOlcrtcVpn() }
        }
    }

    private suspend fun stopEntry(entry: org.olcbox.app.data.model.LocationEntry) {
        when {
            !entry.awgConfig.isNullOrBlank() -> AwgTunnel.down()
            !entry.ovpnConfig.isNullOrBlank() -> withContext(Dispatchers.Main) { OvpnTunnel.down(appContext) }
            else -> withContext(Dispatchers.Main) { stopOlcrtcVpn() }
        }
    }

    private suspend fun waitConnected(entry: org.olcbox.app.data.model.LocationEntry): Boolean {
        val isAwg = !entry.awgConfig.isNullOrBlank()
        val isOvpn = !entry.ovpnConfig.isNullOrBlank()  // OpenVPN (TLS) подключается дольше WireGuard — 20 с, как olcrtc
        val deadline = System.currentTimeMillis() + if (isAwg) 8_000 else 20_000
        while (System.currentTimeMillis() < deadline) {
            if (isAwg && AwgTunnel.hasHandshake()) return true
            if (isOvpn && OvpnTunnel.isConnected()) return true
            if (!isAwg && !isOvpn && OlcboxVpnState.status.value == VpnStatus.Connected) return true
            kotlinx.coroutines.delay(250)
        }
        return false
    }

    private fun startOlcrtcVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_START_VPN
            putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, _connectionMode.value.value)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST, _proxySettings.value.host)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT, _proxySettings.value.port)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, _proxySettings.value.username)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, _proxySettings.value.password)
            putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, _splitTunnelSettings.value.mode.value)
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS,
                ArrayList(_splitTunnelSettings.value.proxyPackages)
            )
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS,
                ArrayList(_splitTunnelSettings.value.bypassPackages)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stopVpn() {
        connectJob?.cancel()  // ручное «Выключить» прерывает и автопереключение
        if (AwgTunnel.isUp()) {
            scope.launch { AwgTunnel.down() }
            return
        }
        if (OvpnTunnel.isUp()) {
            OvpnTunnel.down(appContext)
            return
        }
        stopOlcrtcVpn()
    }

    private fun stopOlcrtcVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_STOP_VPN
        }
        context.startService(intent)
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        val proxy = _proxySettings.value
        return SubscriptionFetchProxy(
            host = AndroidSocksProxySettings.connectHost(proxy.host),
            port = proxy.port,
            username = proxy.username,
            password = proxy.password
        )
    }

    private suspend fun ensureProxySettings() {
        appContext.vpnPrefDataStore.edit { preferences ->
            val username = preferences[KEY_ANDROID_SOCKS_USERNAME]
            val usernameInitialized = preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] == true
            if (username.isNullOrBlank() || (!usernameInitialized && username == LEGACY_DEFAULT_USERNAME)) {
                preferences[KEY_ANDROID_SOCKS_USERNAME] = generateProxyUsername()
            }
            preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
            if (preferences[KEY_ANDROID_SOCKS_PASSWORD].isNullOrBlank()) {
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = generateProxyPassword()
            }
            preferences[KEY_ANDROID_SOCKS_HOST] = AndroidSocksProxySettings.sanitizeHost(
                preferences[KEY_ANDROID_SOCKS_HOST]
            )
            preferences[KEY_ANDROID_SOCKS_PORT] = AndroidSocksProxySettings.sanitizePort(
                preferences[KEY_ANDROID_SOCKS_PORT]
            )
        }
    }

    private suspend fun loadInstalledApps(): List<AndroidInstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        val launcherApps = resolveInfos
            .mapNotNull { it.activityInfo?.applicationInfo }

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        (launcherApps + installedApps)
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }
            .map { appInfo ->
                AndroidInstalledApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    isSystem = appInfo.isSystemApp()
                )
            }
            .sortedWith(compareBy<AndroidInstalledApp> { it.label.lowercase() }.thenBy { it.packageName })
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return flags and systemFlags != 0
    }

    private fun generateProxyPassword(): String {
        return buildString(PROXY_PASSWORD_LENGTH) {
            repeat(PROXY_PASSWORD_LENGTH) {
                append(PROXY_PASSWORD_ALPHABET[random.nextInt(PROXY_PASSWORD_ALPHABET.length)])
            }
        }
    }

    private fun generateProxyUsername(): String {
        return buildString(PROXY_USERNAME_PREFIX.length + PROXY_USERNAME_RANDOM_LENGTH) {
            append(PROXY_USERNAME_PREFIX)
            repeat(PROXY_USERNAME_RANDOM_LENGTH) {
                append(PROXY_USERNAME_ALPHABET[random.nextInt(PROXY_USERNAME_ALPHABET.length)])
            }
        }
    }

    private fun Set<String>.toggle(value: String): Set<String> {
        return if (value in this) this - value else this + value
    }

    private fun updateSplitTunnelSettings(settings: AndroidSplitTunnelSettings) {
        _splitTunnelSettings.value = settings
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS] = settings.proxyPackages
                preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS] = settings.bypassPackages
            }
        }
        restartTunnelForApps()
    }

    private data class AndroidAppPreferences(
        val mode: AndroidConnectionMode,
        val proxy: AndroidSocksProxySettings,
        val splitTunnel: AndroidSplitTunnelSettings,
        val autoFailoverEnabled: Boolean
    )

    private companion object {
        const val LEGACY_DEFAULT_USERNAME = "olcbox"
        const val PROXY_USERNAME_PREFIX = "olcbox"
        const val PROXY_USERNAME_RANDOM_LENGTH = 8
        const val MAX_SOCKS_USERNAME_LENGTH = 64
        const val PROXY_PASSWORD_LENGTH = 24
        const val MAX_SOCKS_PASSWORD_LENGTH = 64
        const val PROXY_USERNAME_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val PROXY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4
        val random = SecureRandom()
    }
}
