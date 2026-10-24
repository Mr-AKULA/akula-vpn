package org.olcbox.app.vpn.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths

/**
 * AKULA (24.10.2026): помощники туннеля (tun2socks, olcRTC) живут, пока жива программа.
 *
 * Если AKULA VPN закрывали аварийно, снимали в диспетчере задач или удаляли во время подключения,
 * tun2socks оставался работать: адаптер Olcbox держал маршруты 0.0.0.0/1 и 128.0.0.0/1 и DNS 127.0.0.1,
 * и у компьютера пропадал интернет (проверено на виртуалке). Windows Job Object с KILL_ON_JOB_CLOSE закрывает
 * всё, что в него положено, как только закрывается последний описатель — то есть вместе с программой.
 */
internal object WindowsProcessJob {
    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, length: Int): Boolean
        fun OpenProcess(access: Int, inheritHandle: Boolean, pid: Int): Pointer?
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val EXTENDED_LIMIT_INFO_SIZE = 144 // JOBOBJECT_EXTENDED_LIMIT_INFORMATION, 64-bit
    private const val LIMIT_FLAGS_OFFSET = 16
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private const val PROCESS_SET_QUOTA_AND_TERMINATE = 0x0100 or 0x0001

    private val kernel32: Kernel32? by lazy {
        if (DesktopPaths.os != DesktopOs.Windows) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    // Описатель держим до конца жизни программы: закроется вместе с ней — и Windows закроет помощников
    private val job: Pointer? by lazy {
        val k = kernel32 ?: return@lazy null
        val handle = k.CreateJobObjectW(null, null) ?: return@lazy null
        val info = Memory(EXTENDED_LIMIT_INFO_SIZE.toLong()).apply { clear() }
        info.setInt(LIMIT_FLAGS_OFFSET.toLong(), JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
        if (k.SetInformationJobObject(handle, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, EXTENDED_LIMIT_INFO_SIZE)) {
            handle
        } else {
            k.CloseHandle(handle)
            null
        }
    }

    /** Привязать процесс-помощник к программе. true — Windows закроет его вместе с AKULA VPN */
    fun bind(process: Process): Boolean = runCatching {
        val k = kernel32 ?: return false
        val j = job ?: return false
        val handle = k.OpenProcess(PROCESS_SET_QUOTA_AND_TERMINATE, false, process.pid().toInt()) ?: return false
        try {
            k.AssignProcessToJobObject(j, handle)
        } finally {
            k.CloseHandle(handle)
        }
    }.getOrDefault(false)

    /**
     * Помощники, оставшиеся от прошлого запуска (версии до 1.2.9 или аварийное закрытие), — закрываем,
     * иначе они держат маршруты туннеля без самого туннеля. Вызывать, когда своих помощников ещё нет.
     */
    fun killStaleHelpers(log: (String) -> Unit) {
        if (DesktopPaths.os != DesktopOs.Windows) return
        val names = listOf("tun2socks-windows-amd64.exe", "olcrtc-windows-amd64.exe")
        val me = ProcessHandle.current()
        ProcessHandle.allProcesses()
            .filter { handle ->
                val command = handle.info().command().orElse("").lowercase()
                names.any { command.endsWith(it) } && handle.parent().map { it.pid() != me.pid() }.orElse(true)
            }
            .forEach { handle ->
                log("Закрываю оставшийся от прошлого запуска ${handle.info().command().orElse("")} (pid ${handle.pid()})")
                handle.destroyForcibly()
            }
    }
}
