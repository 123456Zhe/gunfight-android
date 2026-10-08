package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    private fun msg(type: String, dataJson: String): Packet.Message {
        val pkt = parsePacket("""{"type":"$type","data":$dataJson}""")
        assertTrue(pkt is Packet.Message)
        return pkt as Packet.Message
    }

    @Test fun playerUpdateStringKeys() {
        val s = GameSnapshot()
        s.apply(msg("player_update", """{"2":{"pos":[900.0,910.0],"angle":90.0,"health":100,"ammo":30,"armor":0,"name":"Bob","is_dead":false,"weapon_type":"gun","grenades":1},"3":{"pos":[100.0,100.0],"angle":0.0,"health":80,"ammo":10,"armor":0,"name":"\u7231","is_dead":true,"weapon_type":"melee","grenades":0}}"""))
        assertEquals(2, s.players.size)
        val bob = s.players[2]!!
        assertEquals(900.0, bob.posX, 1e-9)
        assertEquals("Bob", bob.name)
        assertFalse(bob.isDead)
        assertTrue(s.players[3]!!.isDead)
    }

    @Test fun bulletsReplaceSemantics() {
        val s = GameSnapshot()
        s.apply(msg("bullets_update", """[{"id":1,"pos":[10.0,20.0],"dir":[1.0,0.0],"owner":2,"time":1.0,"last_update":1.0}]"""))
        assertEquals(1, s.bullets.size)
        assertEquals(10.0, s.bullets[0].x, 1e-9)
        s.apply(msg("bullets_update", "[]"))
        assertTrue(s.bullets.isEmpty())
    }

    @Test fun grenadesItemsDoors() {
        val s = GameSnapshot()
        s.apply(msg("grenade_update", """[{"id":7,"pos":[5.0,6.0],"velocity":[1.0,1.0],"owner_id":2,"spawn_time":1.0,"exploded":false,"explosion_pos":null}]"""))
        assertEquals(1, s.grenades.size)
        s.apply(msg("item_update", """{"items":[{"id":3,"type":"HEALTH_PACK","pos":[100.0,200.0],"is_active":true,"respawn_time_remaining":0.0}]}"""))
        assertEquals("HEALTH_PACK", s.items[3]!!.type)
        s.apply(msg("door_update", """{"door_id":0,"state":{"is_open":true,"version":4}}"""))
        assertEquals(true, s.doors["0"]!!["is_open"])
    }

    @Test fun combatFeedbackSplit() {
        val s = GameSnapshot()
        s.apply(msg("combat_feedback", """{"kind":"kill","attacker_id":2,"target_id":3,"attacker_name":"A","target_name":"B","damage":20.0,"damage_type":"bullet","target_pos":[1.0,2.0],"time":9.0}"""))
        assertEquals(1, s.kills.size)
        assertNull(s.lastHit)
        s.apply(msg("combat_feedback", """{"kind":"hit","attacker_id":2,"target_id":3,"attacker_name":"A","target_name":"B","damage":20.0,"damage_type":"bullet","target_pos":null,"time":9.1}"""))
        assertEquals(1, s.kills.size)
        assertNotNull(s.lastHit)
    }

    @Test fun respawnUpdatesPlayer() {
        val s = GameSnapshot()
        s.apply(msg("player_update", """{"4":{"pos":[10.0,10.0],"angle":0.0,"health":0,"ammo":0,"armor":0,"name":"D","is_dead":true,"weapon_type":"gun","grenades":0}}"""))
        assertTrue(s.players[4]!!.isDead)
        s.apply(msg("respawn", """{"player_id":4,"pos":[900.0,900.0],"health":100}"""))
        val p = s.players[4]!!
        assertFalse(p.isDead)
        assertEquals(900.0, p.posX, 1e-9)
    }

    @Test fun angleConvention() {
        // 0deg=+x, 90deg=up (y-down => dy negative), CCW positive
        assertEquals(0.0, Angles.of(1.0, 0.0), 1e-9)
        assertEquals(90.0, Angles.of(0.0, -1.0), 1e-9)
        assertEquals(-90.0, Angles.of(0.0, 1.0), 1e-9)
        assertEquals(180.0, Angles.of(-1.0, 0.0), 1e-9)
        val (dx, dy) = Angles.dir(90.0)
        assertEquals(0.0, dx, 1e-9)
        assertEquals(-1.0, dy, 1e-9)
    }
}

class HandshakeTest {
    @Test fun fullFlowAndTimeout() {
        var now = 0L
        val hs = Handshake(nowMs = { now })
        assertEquals(Handshake.State.IDLE, hs.state)
        hs.onProbeSent()
        hs.onServerInfo(Packet.ServerInfo("id", "srv", 1, 10, "1.0"))
        hs.onConnectSent()
        assertFalse(hs.checkTimeout())
        now = 11_000 // past 10s connection timeout
        assertTrue(hs.checkTimeout())
        assertEquals(Handshake.State.FAILED, hs.state)
    }

    @Test fun roomFull() {
        val hs = Handshake()
        hs.onProbeSent()
        hs.onConnectSent()
        val pkt = parsePacket("""{"type":"room_full"}""") as Packet.Message
        assertTrue(hs.onMessage(pkt))
        assertEquals(Handshake.State.ROOM_FULL, hs.state)
    }
}
