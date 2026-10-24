package org.olcbox.app.vpn

import org.olcbox.app.akula.activeLocationWithWake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.olcbox.app.akula.AkulaMail
import org.olcbox.app.akula.AkulaMailAccountStore
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.app.vpn.desktop.DesktopNativeAssets
import org.olcbox.app.vpn.desktop.DesktopDnsResolver
import org.olcbox.app.vpn.desktop.DesktopProxyController
import org.olcbox.app.vpn.desktop.LinuxPrivilege
import org.olcbox.app.vpn.desktop.LinuxTunController
import org.olcbox.app.vpn.desktop.OlcRtcCommand
import org.olcbox.app.vpn.desktop.PacServer
import org.olcbox.app.vpn.desktop.WindowsAwgController
import org.olcbox.app.vpn.desktop.WindowsOvpnController
import org.olcbox.app.vpn.desktop.WindowsTunController
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.olcbox.app.ui.i18n.tr

class DesktopVpnManager private constructor(
    private val locationsRepository: LocationsRepository,
    private val proxyController: DesktopProxyController = DesktopProxyController.current(),
    private val pacServer: PacServer = PacServer()
) : VpnManager {

    constructor(locationsRepository: LocationsRepository) : this(
        locationsRepository = locationsRepository,
        proxyController = DesktopProxyController.current(),
        pacServer = PacServer()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(DesktopSocksProxySettings())
    val socksProxySettings: StateFlow<DesktopSocksProxySettings> = _socksProxySettings.asStateFlow()

    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var tunLogJob: Job? = null
    private var processWatchJob: Job? = null
    private var tunProcessWatchJob: Job? = null
    private var process: Process? = null
    private var tunProcess: Process? = null
    private var olcRtcConfigPath: Path? = null
    private var activeDesktopMode: DesktopMode? = null
    private var generation = 0L
    private val linuxTunController = LinuxTunController(::addLog)
    private val windowsTunController = WindowsTunController(::addLog)
    private val windowsAwgController = WindowsAwgController(::addLog)
    @Volatile private var awgActive = false
    private val windowsOvpnController = WindowsOvpnController(::addLog) { reason ->
        // openvpn.exe завершился сам (сервер пропал, сеть) — показываем, а не висим в «Подключено»
        if (ovpnActive) {
            ovpnActive = false
            addLog(reason)
            setStatus(VpnStatus.Error(reason))
        }
    }
    @Volatile private var ovpnActive = false

    // AKULA: звонок белых списков спит, пока не нужен. Почта сервера и код — из подписки, отправитель — своя почта
    // пользователя из настроек; её нет — открываем готовое письмо в почтовой программе (как страница включения).
    // «Стоп» привратник выполнит, когда в звонке никого не останется (Павел 23.10.2026)
    @Volatile private var wakeLocation: LocationConfig? = null

    // Режим TUN на Windows (Павел 23.10.2026, журнал Насти): сокеты olcRTC — к реальному адаптеру, мимо туннеля;
    // DNS системы — через туннель по TCP (SOCKS olcRTC не умеет UDP)
    @Volatile private var olcBindInterface: Int? = null
    private var dnsForwarder: org.olcbox.app.vpn.desktop.WindowsDnsForwarder? = null

    private fun wakeMail(location: LocationConfig, action: String, interactive: Boolean, attempts: Int = 4): Boolean {
        // Ящик сервера из подписки — команда прямо в него (IMAP), как в сборке Павла: работает и при белых списках
        if (location.hasServerMailbox) {
            addLog("AKULA: «$action» — в ящик сервера")
            if (org.olcbox.app.akula.AkulaServerMailbox.command(
                    location.wakeEmail, location.wakePassword, location.wakeCode, action,
                    log = ::addLog, attempts = attempts
                )
            ) {
                return true
            }
        }
        val account = AkulaMailAccountStore.load()
        if (account.ok()) {
            addLog("AKULA: «$action» — письмо на почту сервера")
            if (AkulaMail.command(account, location.wakeEmail, location.wakeCode, action, log = ::addLog, attempts = attempts)) {
                return true
            }
            // Сеть закрывает отправку почты (бывает у хостингов и сетей) — остаётся почтовая программа
        }
        if (!interactive) return false
        addLog("AKULA: открываю письмо «$action» в почтовой программе")
        return runCatching {
            val uri = java.net.URI(AkulaMailAccountStore.mailto(location.wakeEmail, action, location.wakeCode))
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.MAIL)) desktop.mail(uri) else desktop.browse(uri)
        }.onFailure { addLog("AKULA: почтовая программа не открылась: ${it.message}") }.isSuccess
    }

    private fun sendStopInBackground() {
        val prev = wakeLocation ?: return
        wakeLocation = null
        scope.launch(Dispatchers.IO) { wakeMail(prev, "stop", interactive = false) }
    }

    // Автопереключение серверов — как на телефоне (Павел 16.10 и 23.10.2026): выбранное не подключилось —
    // пробуем следующие по списку. По умолчанию выключено, включает сам пользователь
    private val prefs by lazy { com.russhwolf.settings.Settings() }
    private val _autoFailoverEnabled = MutableStateFlow(
        runCatching { prefs.getBoolean(KEY_AUTO_FAILOVER, false) }.getOrDefault(false)
    )
    val autoFailoverEnabled: StateFlow<Boolean> = _autoFailoverEnabled.asStateFlow()

    fun setAutoFailoverEnabled(enabled: Boolean) {
        _autoFailoverEnabled.value = enabled
        runCatching { prefs.putBoolean(KEY_AUTO_FAILOVER, enabled) }
    }

    private suspend fun tryOtherLocations(requestGeneration: Long) {
        if (_status.value !is VpnStatus.Error) return
        val first = locationsRepository.getActiveLocation()?.storageId
        val others = runCatching { locationsRepository.getAllLocations() }.getOrDefault(emptyList())
            .filter { it.location.isComplete() && it.storageId != first }
        for (entry in others) {
            if (requestGeneration != generation) return
            addLog("Автопереключение: пробую «${entry.location.displayName()}»")
            stopDesktopMode(finalStatus = false)
            locationsRepository.setActiveLocationId(entry.storageId)
            startDesktopMode(requestGeneration, isRestart = false)
            if (_status.value !is VpnStatus.Error) return
        }
    }

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                        _status.value is VpnStatus.Connecting ||
                        _status.value is VpnStatus.Reconnecting ||
                        process != null ||
                        tunProcess != null

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting desktop VPN for selected location")
                    stopDesktopMode(finalStatus = false)

                    if (requestGeneration != generation) return@withLock
                }

                startDesktopMode(requestGeneration, isRestart = shouldRestart)
                if (_autoFailoverEnabled.value) tryOtherLocations(requestGeneration)
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }
            sendStopInBackground()
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return OlcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = locationsRepository.getDeviceIdentity()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val currentStatus = status.value
        if (currentStatus !is VpnStatus.Connected &&
            currentStatus !is VpnStatus.Reconnecting
        ) {
            return null
        }

        val socks = _socksProxySettings.value.normalized()
        return SubscriptionFetchProxy(
            host = socks.host,
            port = socks.port,
            username = socks.username,
            password = socks.password
        )
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = _socksProxySettings.value.copy(
            port = port,
            username = username,
            password = password
        ).normalized()
        _socksProxySettings.value = settings
        pacServer.updateSocksTarget(
            socksHost = settings.host,
            socksPort = settings.port,
            socksUsername = settings.username,
            socksPassword = settings.password
        )
    }

    fun updateSocksProxySettings(settings: DesktopSocksProxySettings) {
        val normalized = settings.normalized()
        _socksProxySettings.value = normalized
        pacServer.updateSocksTarget(
            socksHost = normalized.host,
            socksPort = normalized.port,
            socksUsername = normalized.username,
            socksPassword = normalized.password
        )
    }

    fun close() {
        runBlocking {
            generation++

            mutex.withLock {
                stopDesktopMode(finalStatus = true)
            }

            // Приложение закрывают — одна попытка, чтобы не держать выход
            wakeLocation?.let { prev -> wakeLocation = null; runCatching { wakeMail(prev, "stop", interactive = false, attempts = 1) } }
            scope.cancel()
        }
    }

    private suspend fun startDesktopMode(requestGeneration: Long, isRestart: Boolean) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.activeLocationWithWake(::addLog)
        val location = active?.location?.normalized()

        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting desktop proxy")
            return
        }

        // Переключились с белых списков на другое подключение — тот звонок больше не нужен
        if (wakeLocation != null && wakeLocation?.wakeCode != location.wakeCode) sendStopInBackground()

        // AmneziaWG/WireGuard (профиль из консоли AKULA): на Windows — служба официальной AmneziaWG
        if (location.isAwg) {
            if (DesktopPaths.os != DesktopOs.Windows) {
                setStatus(VpnStatus.Error("AmneziaWG на этой системе пока не поддерживается"))
                return
            }
            try {
                windowsTunController.ensureAdministratorOrRequestRestart()
                windowsAwgController.up(location.awgConfig)
                awgActive = true
                setStatus(VpnStatus.Connected)
            } catch (e: Exception) {
                addLog("AmneziaWG: ${e.message}")
                setStatus(VpnStatus.Error(e.message ?: "AmneziaWG не подключился"))
            }
            return
        }

        // OpenVPN (профиль из консоли AKULA): на Windows — встроенный openvpn.exe 2.7 с драйвером ovpn-dco
        if (location.isOvpn) {
            if (DesktopPaths.os != DesktopOs.Windows) {
                setStatus(VpnStatus.Error(tr(
                    "OpenVPN на этой системе пока не поддерживается",
                    "OpenVPN is not supported on this system yet"
                )))
                return
            }
            try {
                windowsTunController.ensureAdministratorOrRequestRestart()
                windowsOvpnController.up(location.ovpnConfig)
                ovpnActive = true
                setStatus(VpnStatus.Connected)
            } catch (e: Exception) {
                addLog("OpenVPN: ${e.message}")
                setStatus(VpnStatus.Error(e.message ?: tr("OpenVPN не подключился", "OpenVPN failed to connect")))
            }
            return
        }

        try {
            val socksSettings = _socksProxySettings.value.normalized()
            val desktopMode = DesktopMode.from(socksSettings.routingMode)
            activeDesktopMode = desktopMode

            if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.ensureAdministratorOrRequestRestart()
            }
            olcBindInterface = if (desktopMode == DesktopMode.WindowsTun) {
                windowsTunController.physicalInterfaceIndex().also {
                    addLog(if (it != null) "olcRTC: сокеты — через адаптер $it, мимо туннеля" else "olcRTC: реальный адаптер не найден")
                }
            } else {
                null
            }

            // Звонок белых списков спит, пока его не разбудят письмом, и просыпается 20–40 секунд. Клиент olcRTC,
            // не дождавшись сервера в комнате, выходит — не сдаёмся сразу, а пробуем ещё минуту (Павел 23.10.2026).
            val woke = location.hasWake && withContext(Dispatchers.IO) {
                wakeLocation = location
                wakeMail(location, "start", interactive = !isRestart)
            }
            var wakeDeadline = 0L
            var attempt = 0
            while (true) {
                attempt++
                val ready = CompletableDeferred<Unit>()
                val startupFailure = CompletableDeferred<String>()
                process = startOlcRtcProcessWithFallback(
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = true,
                    privileged = desktopMode == DesktopMode.LinuxTun
                )
                val attemptProcess = process ?: error("olcRTC process is missing")
                try {
                    waitForOlcRtcReady(
                        process = attemptProcess,
                        ready = ready,
                        startupFailure = startupFailure,
                        socksPort = socksSettings.port,
                        requestGeneration = requestGeneration
                    )
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { attemptProcess.destroyForcibly() }
                    process = null
                    val now = System.currentTimeMillis()
                    if (wakeDeadline == 0L) wakeDeadline = now + if (woke) OLC_WAKE_SENT_WAIT_MS else OLC_WAKE_WAIT_MS
                    if (now + OLC_RETRY_DELAY_MS >= wakeDeadline) {
                        addLog("olcRTC: ${e.message}; attempts: $attempt")
                        error(when {
                            woke -> tr(
                                "Звонок не отвечает, хотя письмо отправлено. Подождите минуту и подключитесь снова",
                                "The call does not answer although the wake e-mail was sent. Wait a minute and connect again"
                            )
                            location.hasWake -> tr(
                                "Звонок не отвечает. Отправьте письмо «Включить» (или укажите свою почту Яндекса в настройках) и через минуту подключитесь снова",
                                "The call does not answer. Send the wake e-mail (or set your Yandex mail in settings) and connect again in a minute"
                            )
                            else -> tr(
                                "Звонок не отвечает. Включите его письмом со страницы включения и через минуту подключитесь снова",
                                "The call does not answer. Wake it with the e-mail from the wake page and connect again in a minute"
                            )
                        })
                    }
                    addLog("olcRTC: ${e.message}. The call may still be waking up — retry ${attempt + 1}")
                    delay(OLC_RETRY_DELAY_MS)
                    if (requestGeneration != generation) {
                        throw CancellationException("Desktop start superseded")
                    }
                }
            }

            val olcRtcProcess = process ?: error("olcRTC process is missing")

            if (requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            when (desktopMode) {
                DesktopMode.LinuxTun -> startLinuxTun(socksSettings.port, requestGeneration)
                DesktopMode.WindowsTun -> startWindowsTun(socksSettings, requestGeneration)
                DesktopMode.SystemProxy -> startSystemProxy(socksSettings, requestGeneration)
                DesktopMode.LocalSocks -> Unit
            }

            if (!olcRtcProcess.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            startProcessExitWatchers(
                desktopMode = desktopMode,
                olcRtcProcess = olcRtcProcess,
                currentTunProcess = tunProcess,
                requestGeneration = requestGeneration
            )

            setStatus(VpnStatus.Connected)
            addLog(
                when (desktopMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN connected"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN connected"
                    DesktopMode.SystemProxy -> "Desktop proxy connected"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy connected"
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                addLog("Desktop start cancelled")
            } else {
                addLog("Desktop start failed: ${e.message}")
            }

            stopDesktopMode(finalStatus = false)

            if (e !is CancellationException && requestGeneration == generation) {
                setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
            }
        }
    }

    private suspend fun startLinuxTun(socksPort: Int, requestGeneration: Long) {
        val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
        tunProcess = linuxTunController.start(hevBinary, socksPort)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
    }

    private suspend fun startWindowsTun(socksSettings: DesktopSocksProxySettings, requestGeneration: Long) {
        val tun2SocksBinary = DesktopNativeAssets.resolveWindowsTun2SocksBinary()
        val forwarder = org.olcbox.app.vpn.desktop.WindowsDnsForwarder(
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            addLog = ::addLog
        )
        val dns = runCatching { forwarder.start(); dnsForwarder = forwarder; "127.0.0.1" }
            .getOrElse {
                addLog("DNS: локальный пересыльщик не запустился (${it.message}) — DNS туннеля 1.1.1.1")
                "1.1.1.1"
            }
        tunProcess = windowsTunController.start(tun2SocksBinary, socksSettings.port, dns)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }

        startTunLogReader(tunProcess ?: error("tun2socks process is missing"))
    }

    private suspend fun startSystemProxy(
        socksSettings: DesktopSocksProxySettings,
        requestGeneration: Long
    ) {
        pacServer.start(
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUsername = socksSettings.username,
            socksPassword = socksSettings.password
        )
        proxyController.enable(pacServer.url)

        if (requestGeneration != generation) {
            throw CancellationException("Desktop start superseded")
        }
    }

    private enum class DesktopMode {
        LinuxTun,
        WindowsTun,
        SystemProxy,
        LocalSocks;

        companion object {
            fun from(mode: DesktopRoutingMode): DesktopMode {
                return when (mode.resolveForCurrentPlatform()) {
                    DesktopRoutingMode.Tun -> when (DesktopPaths.os) {
                        DesktopOs.Linux -> LinuxTun
                        DesktopOs.Windows -> WindowsTun
                        DesktopOs.MacOS,
                        DesktopOs.Other -> SystemProxy
                    }
                    DesktopRoutingMode.SystemProxy -> SystemProxy
                    DesktopRoutingMode.LocalSocks -> LocalSocks
                    DesktopRoutingMode.Auto -> error("Auto desktop mode was not resolved")
                }
            }
        }
    }

    private fun startOlcRtcProcessWithFallback(
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean
    ): Process {
        val binaries = DesktopNativeAssets.resolveOlcRtcBinaryCandidates()
        val dnsServer = location.dnsServer.ifBlank { DesktopDnsResolver.current() }
        var lastException: Exception? = null

        addLog("Using DNS server $dnsServer for olcRTC")

        for (binary in binaries) {
            try {
                return startOlcRtcProcess(
                    binary = binary,
                    location = location,
                    socksSettings = socksSettings,
                    ready = ready,
                    startupFailure = startupFailure,
                    logOutput = logOutput,
                    privileged = privileged,
                    dnsServer = dnsServer
                )
            } catch (e: Exception) {
                lastException = e

                if (binary == binaries.last()) break

                addLog("olcRTC start failed for ${binary.fileName}: ${e.message}. Retrying with fallback binary.")
            }
        }

        throw lastException ?: error("olcRTC binary failed to start")
    }

    private suspend fun stopDesktopMode(finalStatus: Boolean) {
        if (ovpnActive || windowsOvpnController.isRunning) {
            setStatus(VpnStatus.Stopping)
            ovpnActive = false
            runCatching { windowsOvpnController.down() }.onFailure { addLog("OpenVPN: ${it.message}") }
            if (finalStatus) setStatus(VpnStatus.Disconnected)
            return
        }
        if (awgActive) {
            setStatus(VpnStatus.Stopping)
            runCatching { windowsAwgController.down() }.onFailure { addLog("AmneziaWG: ${it.message}") }
            awgActive = false
            if (finalStatus) setStatus(VpnStatus.Disconnected)
            return
        }
        if (_status.value is VpnStatus.Disconnected && process == null && tunProcess == null) {
            cancelProcessJobs()
            return
        }

        setStatus(VpnStatus.Stopping)
        cancelProcessJobs()

        val stoppedMode = activeDesktopMode
        when (stoppedMode) {
            DesktopMode.LinuxTun -> {
                runCatching {
                    linuxTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Linux TUN stop failed: ${it.message}")
                }
                tunProcess = null
            }
            DesktopMode.WindowsTun -> {
                runCatching {
                    windowsTunController.stop(tunProcess)
                }.onFailure {
                    addLog("Windows TUN stop failed: ${it.message}")
                }
                tunProcess = null
                dnsForwarder?.stop()
                dnsForwarder = null
            }
            DesktopMode.SystemProxy -> {
                runCatching {
                    proxyController.restore()
                }.onFailure {
                    addLog("Proxy restore failed: ${it.message}")
                }
            }
            DesktopMode.LocalSocks,
            null -> Unit
        }

        if (stoppedMode == DesktopMode.SystemProxy) {
            pacServer.stop()
        }
        activeDesktopMode = null

        stopProcess(process)
        process = null
        deleteOlcRtcConfig()

        if (finalStatus) {
            setStatus(VpnStatus.Disconnected)
            addLog(
                when (stoppedMode) {
                    DesktopMode.LinuxTun -> "Desktop Linux TUN stopped"
                    DesktopMode.WindowsTun -> "Desktop Windows TUN stopped"
                    DesktopMode.SystemProxy -> "Desktop proxy stopped"
                    DesktopMode.LocalSocks -> "Desktop local SOCKS proxy stopped"
                    null -> "Desktop connection stopped"
                }
            )
        }
    }

    private fun cancelProcessJobs() {
        processWatchJob?.cancel()
        processWatchJob = null

        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = null

        logJob?.cancel()
        logJob = null

        tunLogJob?.cancel()
        tunLogJob = null
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksSettings: DesktopSocksProxySettings,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        logOutput: Boolean,
        privileged: Boolean,
        dnsServer: String
    ): Process {
        val config = location.normalized()
        val provider = OlcRtcCommand.desktopProviderArg(config.bypassProvider)
        val olcRtcCommand = OlcRtcCommand(
            binary = binary,
            location = config,
            socksHost = socksSettings.host,
            socksPort = socksSettings.port,
            socksUser = socksSettings.username,
            socksPass = socksSettings.password,
            dnsServer = dnsServer
        )
        val configPath = writeOlcRtcClientConfig(olcRtcCommand)
        val command = olcRtcCommand.args(configPath)

        addLog("Starting olcRTC provider=$provider, transport=${config.transport}, room=${config.id}, port=${socksSettings.port}")

        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }

        val processBuilder = ProcessBuilder(
            if (privileged) LinuxPrivilege.command(command) else command
        ).redirectErrorStream(true)

        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        // Закрытый stdin = «выйди из звонка вежливо»: на Windows destroy() — это TerminateProcess, olcRTC не успевал
        // попрощаться, и сервер видел уход только через минуту (по таймауту)
        processBuilder.environment()["OLCRTC_STDIN_LIFELINE"] = "1"
        olcBindInterface?.let { processBuilder.environment()["OLCRTC_BIND_IFINDEX"] = it.toString() }
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"

        val startedProcess = try {
            processBuilder.start()
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(configPath) }
            if (olcRtcConfigPath == configPath) {
                olcRtcConfigPath = null
            }
            throw e
        }

        val readerJob = scope.launch {
            try {
                startedProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        if (logOutput) {
                            val message = "rtc: $line"
                            addLog(message)
                            println(message)
                        }

                        if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                            ready.complete(Unit)
                        }

                        if (isFatalOlcRtcStartupLine(line)) {
                            startupFailure.complete(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // Process stdout may close while stopping or after a remote disconnect.
            }
        }

        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }

        return startedProcess
    }

    private fun writeOlcRtcClientConfig(command: OlcRtcCommand): Path {
        val runtimeDir = DesktopPaths.appDataDir().resolve("runtime")
        Files.createDirectories(runtimeDir)
        val path = Files.createTempFile(runtimeDir, "olcrtc-client-", ".yaml")
        Files.writeString(path, command.yaml(), StandardCharsets.UTF_8)
        deleteOlcRtcConfig()
        olcRtcConfigPath = path
        return path
    }

    private fun deleteOlcRtcConfig() {
        olcRtcConfigPath?.let { path ->
            runCatching { Files.deleteIfExists(path) }
        }
        olcRtcConfigPath = null
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()

        tunLogJob = scope.launch {
            try {
                target.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break

                        val message = "tun: $line"
                        addLog(message)
                        println(message)
                    }
                }
            } catch (_: IOException) {
                // TUN stdout may close while the process is being stopped.
            }
        }
    }

    private fun startProcessExitWatchers(
        desktopMode: DesktopMode,
        olcRtcProcess: Process,
        currentTunProcess: Process?,
        requestGeneration: Long
    ) {
        startOlcRtcExitWatcher(olcRtcProcess, requestGeneration)

        when (desktopMode) {
            DesktopMode.LinuxTun,
            DesktopMode.WindowsTun -> startTunExitWatcher(
                currentTunProcess ?: error("TUN process is missing"),
                requestGeneration
            )
            DesktopMode.SystemProxy,
            DesktopMode.LocalSocks -> {
                tunProcessWatchJob?.cancel()
                tunProcessWatchJob = null
            }
        }
    }

    private fun startOlcRtcExitWatcher(target: Process, requestGeneration: Long) {
        processWatchJob?.cancel()
        processWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || process !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "olcRTC process exited unexpectedly with code $exitCode",
                        errorMessage = "olcRTC exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private fun startTunExitWatcher(target: Process, requestGeneration: Long) {
        tunProcessWatchJob?.cancel()
        tunProcessWatchJob = scope.launch {
            val exitCode = waitForProcessExit(target) ?: return@launch
            if (!isActive) return@launch

            scope.launch {
                mutex.withLock {
                    if (requestGeneration != generation || tunProcess !== target) return@withLock

                    handleUnexpectedProcessExit(
                        logMessage = "TUN process exited unexpectedly with code $exitCode",
                        errorMessage = "TUN process exited unexpectedly (code $exitCode)",
                        requestGeneration = requestGeneration
                    )
                }
            }
        }
    }

    private suspend fun handleUnexpectedProcessExit(
        logMessage: String,
        errorMessage: String,
        requestGeneration: Long
    ) {
        addLog(logMessage)
        stopDesktopMode(finalStatus = false)

        if (requestGeneration == generation) {
            setStatus(VpnStatus.Error(errorMessage))
        }
    }

    private fun waitForProcessExit(target: Process): Int? {
        return try {
            target.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        startupFailure: CompletableDeferred<String>,
        socksPort: Int,
        requestGeneration: Long? = null
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (ready.isCompleted || canConnectToSocks(socksPort)) {
                waitForOlcRtcStartupStability(process, startupFailure, requestGeneration)
                return
            }

            if (!process.isAlive) {
                error("olcRTC exited before SOCKS5 was ready")
            }

            delay(READY_POLL_INTERVAL_MS)
        }

        error("olcRTC start timed out")
    }

    private suspend fun waitForOlcRtcStartupStability(
        process: Process,
        startupFailure: CompletableDeferred<String>,
        requestGeneration: Long?
    ) {
        val deadline = System.currentTimeMillis() + OLC_STARTUP_STABILITY_MS
        while (System.currentTimeMillis() < deadline) {
            if (requestGeneration != null && requestGeneration != generation) {
                throw CancellationException("Desktop start superseded")
            }

            if (startupFailure.isCompleted) {
                error("olcRTC failed before desktop proxy was enabled: ${startupFailure.await()}")
            }

            if (!process.isAlive) {
                error("olcRTC exited before desktop proxy was enabled")
            }

            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private fun canConnectToSocks(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port),
                    TCP_CONNECT_TIMEOUT_MS.toInt()
                )
            }
        }.isSuccess
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (!target.isAlive) return

        runCatching { target.outputStream.close() }
        if (target.waitFor(PROCESS_GRACEFUL_STOP_MS, TimeUnit.MILLISECONDS)) return

        target.toHandle().descendants().forEach {
            it.destroy()
        }

        target.destroy()

        if (!target.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            target.toHandle().descendants().forEach {
                it.destroyForcibly()
            }

            target.destroyForcibly()
            target.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        _logs.update {
            (it + message).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 5_000
        const val OLC_READY_TIMEOUT_MS = 25_000L
        const val OLC_WAKE_SENT_WAIT_MS = 120_000L  // письмо ушло: привратник проверяет почту раз в 20 с, звонок встаёт за 20–40 с
        const val OLC_WAKE_WAIT_MS = 60_000L  // столько ещё ждём просыпающийся звонок после первой неудачи
        const val OLC_RETRY_DELAY_MS = 3_000L
        const val OLC_STARTUP_STABILITY_MS = 1_500L
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val KEY_AUTO_FAILOVER = "akula_auto_failover"
        const val PROCESS_GRACEFUL_STOP_MS = 3_000L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4

        internal fun isFatalOlcRtcStartupLine(line: String): Boolean {
            val text = line.lowercase()
            return "failed to connect link" in text ||
                    "join room failed" in text ||
                    "get room token" in text && "failed" in text ||
                    "transport connect" in text && "failed" in text
        }
    }
}
