package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Test

class JsonTest {
    @Test fun roundTripBasic() {
        val v = mapOf("a" to 1.0, "b" to "x", "c" to true, "d" to null, "e" to listOf(1.0, "z"))
        assertEquals(v, Json.parse(Json.stringify(v)))
    }

    @Test fun chineseEscapes() {
        // Python json.dumps(ensure_ascii=True) emits \uXXXX; parser must handle it
        val parsed = Json.parse("""{"message":"\u52a0\u5165\u4e86\u6e38\u620f"}""")
        @Suppress("UNCHECKED_CAST")
        assertEquals("加入了游戏", (parsed as Map<String, Any?>)["message"])
        // stringify keeps printable unicode raw (valid JSON, decoders accept both)
        val back = Json.parse(Json.stringify(mapOf("message" to "加入了游戏")))
        @Suppress("UNCHECKED_CAST")
        assertEquals("加入了游戏", (back as Map<String, Any?>)["message"])
    }

    @Test fun garbageThrows() {
        try {
            Json.parse("{not json")
            fail("expected exception")
        } catch (_: Exception) {
        }
    }
}

class MessagesTest {
    @Test fun connectRequestRoundTrip() {
        val pkt = parsePacket(buildConnectRequest("Alice"))
        assertTrue(pkt is Packet.Message)
        val msg = pkt as Packet.Message
        assertEquals("connect_request", msg.type)
        @Suppress("UNCHECKED_CAST")
        assertEquals("Alice", (msg.fields as Map<String, Any?>)["player_name"])
    }

    @Test fun heartbeatRoundTrip() {
        val pkt = parsePacket(buildHeartbeat(3, 1234.5)) as Packet.Message
        assertEquals("heartbeat", pkt.type)
        @Suppress("UNCHECKED_CAST")
        val d = pkt.data as Map<String, Any?>
        assertEquals(3.0, d["player_id"])
    }

    @Test fun serverInfoText() {
        val pkt = parsePacket("""server_info:{"id":"abc","name":"srv","players":3,"max_players":10,"version":"1.0"}""")
        assertTrue(pkt is Packet.ServerInfo)
        val info = pkt as Packet.ServerInfo
        assertEquals("abc", info.id)
        assertEquals(3, info.players)
    }

    @Test fun invalidPackets() {
        assertTrue(parsePacket("garbage{{{") is Packet.Invalid)
        assertTrue(parsePacket("""{"no_type":1}""") is Packet.Invalid)
        assertTrue(parsePacket("""[1,2]""") is Packet.Invalid)
        assertTrue(parsePacket("") is Packet.Invalid)
    }

    @Test fun connectResponseTopLevelFields() {
        // reference network.py sends client_id/server_name TOP-LEVEL (not under data)
        val now = longArrayOf(0L)
        val hs = Handshake(nowMs = { now[0] })
        hs.onProbeSent()
        hs.onConnectSent()
        val pkt = parsePacket("""{"type":"connect_response","client_id":5,"server_name":"S","server_time":1.0}""")
        assertTrue(pkt is Packet.Message)
        assertTrue(hs.onMessage(pkt as Packet.Message))
        assertEquals(Handshake.State.CONNECTED, hs.state)
        assertEquals(5, hs.clientId)
        assertEquals("S", hs.serverName)
    }
}
