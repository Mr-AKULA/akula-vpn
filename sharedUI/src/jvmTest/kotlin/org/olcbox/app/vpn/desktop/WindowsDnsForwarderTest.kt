package org.olcbox.app.vpn.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WindowsDnsForwarderTest {
    @Test
    fun forwardsUdpQueryAsTcpThroughSocks() {
        val answer = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 1, 2, 3)
        var connectTarget = ""
        // Поддельный SOCKS5 olcRTC: принимает CONNECT и сам отвечает как DNS по TCP
        val socks = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            socks.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())
                input.readUnsignedByte(); val n = input.readUnsignedByte(); input.skipNBytes(n.toLong())
                output.write(byteArrayOf(5, 0))
                input.skipNBytes(3); input.readUnsignedByte()
                val ip = ByteArray(4).also { input.readFully(it) }
                connectTarget = InetAddress.getByAddress(ip).hostAddress + ":" + input.readUnsignedShort()
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                val len = input.readUnsignedShort(); input.skipNBytes(len.toLong())
                output.writeShort(answer.size); output.write(answer); output.flush()
            }
        }
        val port = DatagramSocket(0).use { it.localPort }
        val forwarder = WindowsDnsForwarder("127.0.0.1", socks.localPort, "", "", listenPort = port, addLog = {})
        forwarder.start()
        try {
            DatagramSocket().use { client ->
                client.soTimeout = 5_000
                val query = byteArrayOf(0x12, 0x34, 1, 0, 0, 1)
                client.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port))
                val buf = ByteArray(512)
                val reply = DatagramPacket(buf, buf.size)
                client.receive(reply)
                assertContentEquals(answer, buf.copyOf(reply.length))
            }
            assertEquals("1.1.1.1:53", connectTarget)
        } finally {
            forwarder.stop()
            socks.close()
        }
    }
}
