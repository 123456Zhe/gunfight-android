package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** Loopback handshake against a fake server socket (no reference server needed). */
class UdpLoopbackTest {
    @Test(timeout = 15000) fun handshakeAndBroadcast() {
        val server = DatagramSocket(0)
        server.soTimeout = 5000
        val port = server.localPort
        val buf = ByteArray(Net.BUFFER_SIZE)

        val serverThread = Thread {
            try {
                // 1. probe
                var p = DatagramPacket(buf, buf.size)
                server.receive(p)
                val first = String(p.data, 0, p.length, StandardCharsets.UTF_8)
                val client = p.socketAddress
                if (first == PROBE_TEXT) {
                    val info = """server_info:{"id":"srv-1","name":"Test","players":0,"max_players":10,"version":"1.0"}"""
                    val b = info.toByteArray(StandardCharsets.UTF_8)
                    server.send(DatagramPacket(b, b.size, client))
                }
                // 2. connect_request (retry until received)
                while (true) {
                    p = DatagramPacket(buf, buf.size)
                    server.receive(p)
                    val text = String(p.data, 0, p.length, StandardCharsets.UTF_8)
                    if (text.contains("connect_request")) break
                }
                fun send(text: String) {
                    val b = text.toByteArray(StandardCharsets.UTF_8)
                    server.send(DatagramPacket(b, b.size, p.socketAddress))
                }
                send("""{"type":"connect_response","client_id":2,"server_name":"Test","server_time":1.0}""")
                send("""{"type":"init_players","data":{"2":{"pos":[900.0,900.0],"angle":0.0,"health":100,"ammo":30,"armor":0,"name":"Phone","is_dead":false,"weapon_type":"gun","grenades":0}}}""")
                send("""{"type":"player_update","data":{"2":{"pos":[901.0,900.0],"angle":90.0,"health":100,"ammo":30,"armor":0,"name":"Phone","is_dead":false,"weapon_type":"gun","grenades":0}}}""")
                send("""{"type":"bullets_update","data":[{"id":1,"pos":[10.0,20.0],"dir":[1.0,0.0],"owner":2,"time":1.0,"last_update":1.0}]}""")
                // 3. expect at least one heartbeat, then stay alive briefly
                val deadline = System.currentTimeMillis() + 4000
                while (System.currentTimeMillis() < deadline) {
                    p = DatagramPacket(buf, buf.size)
                    server.receive(p)
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = GameClient("127.0.0.1", port, "Phone")
        try {
            assertTrue(client.connect())
            assertEquals(2, client.handshake.clientId)
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && client.snapshot.bullets.isEmpty()) Thread.sleep(50)
            assertEquals(1, client.snapshot.players.size)
            assertEquals("Phone", client.snapshot.players[2]!!.name)
            assertEquals(1, client.snapshot.bullets.size)
            assertFalse(client.isTimedOut())
        } finally {
            client.close()
            server.close()
        }
    }
}
