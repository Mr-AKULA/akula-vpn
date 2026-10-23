package org.olcbox.app.vpn.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * AKULA (23.10.2026): DNS системы в режиме TUN. Windows спрашивает DNS по UDP, а SOCKS olcRTC умеет только TCP —
 * запросы в туннеле пропадали (`dial 1.1.1.1:53 … forcibly closed`), сайты открывались через 13–21 с
 * (Windows дожидалась ответа другого адаптера) или не открывались вовсе.
 * Слушаем 127.0.0.1:53 по UDP и пересылаем каждый запрос по TCP (DNS over TCP, RFC 7766) через SOCKS olcRTC.
 */
internal class WindowsDnsForwarder(
    private val socksHost: String,
    private val socksPort: Int,
    private val socksUser: String,
    private val socksPass: String,
    private val upstream: String = UPSTREAM,
    private val listenPort: Int = 53,
    private val addLog: (String) -> Unit
) {
    @Volatile private var socket: DatagramSocket? = null
    private var pool: ExecutorService? = null

    fun start() {
        val udp = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LISTEN_HOST), listenPort))
        }
        socket = udp
        val workers = Executors.newFixedThreadPool(8) { r -> Thread(r, "akula-dns").apply { isDaemon = true } }
        pool = workers
        Thread({
            val buffer = ByteArray(4096)
            while (!udp.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { udp.receive(packet) }.onFailure { return@Thread }
                val query = packet.data.copyOf(packet.length)
                val client = packet.socketAddress
                workers.execute {
                    runCatching { resolve(query) }
                        .onSuccess { answer -> runCatching { udp.send(DatagramPacket(answer, answer.size, client)) } }
                }
            }
        }, "akula-dns-listen").apply { isDaemon = true }.start()
        addLog("DNS: 127.0.0.1:$listenPort → $upstream через туннель (TCP)")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun resolve(query: ByteArray): ByteArray {
        Socket().use { s ->
            s.soTimeout = TIMEOUT_MS
            s.connect(InetSocketAddress(socksHost, socksPort), TIMEOUT_MS)
            val input = DataInputStream(s.getInputStream())
            val output = DataOutputStream(s.getOutputStream())
            socksConnect(input, output)
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val length = input.readUnsignedShort()
            return ByteArray(length).also { input.readFully(it) }
        }
    }

    /** SOCKS5 CONNECT к upstream:53 (RFC 1928), при логине — RFC 1929 */
    private fun socksConnect(input: DataInputStream, output: DataOutputStream) {
        val auth = socksUser.isNotBlank()
        output.write(byteArrayOf(5, 1, if (auth) 2 else 0))
        output.flush()
        val version = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        check(version == 5 && method != 0xFF) { "SOCKS: метод не принят" }
        if (method == 2) {
            val user = socksUser.toByteArray()
            val pass = socksPass.toByteArray()
            output.write(1)
            output.write(user.size); output.write(user)
            output.write(pass.size); output.write(pass)
            output.flush()
            input.readUnsignedByte()
            check(input.readUnsignedByte() == 0) { "SOCKS: логин не принят" }
        }
        val ip = InetAddress.getByName(upstream).address
        output.write(byteArrayOf(5, 1, 0, 1))
        output.write(ip)
        output.writeShort(53)
        output.flush()
        input.readUnsignedByte()
        check(input.readUnsignedByte() == 0) { "SOCKS: соединение не принято" }
        input.readUnsignedByte()
        when (input.readUnsignedByte()) {
            1 -> input.skipNBytes(4)
            4 -> input.skipNBytes(16)
            3 -> input.skipNBytes(input.readUnsignedByte().toLong())
        }
        input.skipNBytes(2)
    }

    companion object {
        const val LISTEN_HOST = "127.0.0.1"
        const val UPSTREAM = "1.1.1.1"
        const val TIMEOUT_MS = 8_000
    }
}
