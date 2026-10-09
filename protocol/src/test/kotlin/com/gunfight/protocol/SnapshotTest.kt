package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Test

class SnapshotTest {
    private fun msg(type: String, dataJson: String): Packet.Message {
        val pkt = parsePacket("""{"type":"$type","data":$dataJson}""")
        assertTrue(pkt is Packet.Message)
        return pkt as Packet.Message
    }

    @Test fun itemPickupRequestShape() {
        val text = buildItemPickup(2, 7)
        val m = Json.parse(text) as Map<String, Any?>
        assertEquals("item_pickup", m["type"])
        @Suppress("UNCHECKED_CAST")
        val d = m["data"] as Map<String, Any?>
        assertEquals(2.0, d["player_id"])
        assertEquals(7.0, d["item_id"])
    }

    @Test fun stalePlayersAreEvicted() {
        val s = GameSnapshot()
        s.localPlayerId = 1
        s.apply(msg("player_update", """{"1":{"pos":[10.0,10.0],"health":100},"2":{"pos":[20.0,20.0],"health":100}}"""), 1000L)
        s.apply(msg("player_update", """{"1":{"pos":[10.0,10.0],"health":100}}"""), 2000L)
        assertEquals(setOf(1, 2), s.players.keys.toSet())
        // pid 2 has not been broadcast for > ttl, pid 1 must never be evicted
        val gone = s.pruneStalePlayers(4000L, 2000L)
        assertEquals(listOf(2), gone)
        assertEquals(setOf(1), s.players.keys.toSet())
        assertEquals(setOf(1), s.playerLastSeen.keys.toSet())
    }

    @Test fun grenadeVanishBecomesExplosion() {
        val s = GameSnapshot()
        s.apply(msg("grenade_update", """[{"id":5,"pos":[30.0,40.0],"owner_id":2,"exploded":false}]"""), 100L)
        assertTrue(s.explosions.isEmpty())
        s.apply(msg("grenade_update", "[]"), 300L)
        assertEquals(1, s.explosions.size)
        assertEquals(30.0, s.explosions[0].x, 1e-9)
        s.apply(msg("grenade_update", "[]"), 400L)
        assertEquals(1, s.explosions.size) // no double report
    }

    @Test fun explodedFlagAlsoReportsOnce() {
        val s = GameSnapshot()
        s.apply(msg("grenade_update", """[{"id":9,"pos":[1.0,2.0],"owner_id":2,"exploded":true}]"""), 100L)
        s.apply(msg("grenade_update", """[{"id":9,"pos":[1.0,2.0],"owner_id":2,"exploded":true}]"""), 200L)
        assertEquals(1, s.explosions.size)
    }

    @Test fun chatAndKillFeedAreBounded() {
        val s = GameSnapshot()
        for (i in 0 until 60) {
            s.apply(msg("combat_feedback", """{"kind":"kill","attacker_name":"A","target_name":"B"}"""), i.toLong())
        }
        assertEquals(GameSnapshot.MAX_KILL_FEED, s.kills.size)
        s.apply(msg("chat_message", """{"player_id":0,"player_name":"系统","message":"A 加入了游戏"}"""), 1L)
        s.apply(msg("chat_history", """{"messages":[{"player_id":9,"player_name":"P","message":"hi"}]}"""), 2L)
        assertEquals(2, s.chat.size)
        assertEquals("A 加入了游戏", s.chat[0].text)
        for (i in 0 until 40) s.apply(msg("chat_message", """{"player_id":1,"player_name":"x","message":"m"}"""), i.toLong())
        assertEquals(GameSnapshot.MAX_CHAT_LINES, s.chat.size)
    }

    @Test fun hitEventCarriesTargetPosition() {
        val s = GameSnapshot()
        s.apply(msg("combat_feedback", """{"kind":"hit","attacker_id":2,"target_id":3,"damage":20.0,"damage_type":"bullet","target_pos":[700.0,800.0]}"""), 5000L)
        val hit = s.lastHit!!
        assertEquals(700.0, hit.targetPosX!!, 1e-9)
        assertEquals(800.0, hit.targetPosY!!, 1e-9)
        assertEquals(5000L, hit.recvMs)
    }

    @Test fun bulletsCarryReceiveTimestamp() {
        val s = GameSnapshot()
        s.apply(msg("bullets_update", """[{"id":1,"pos":[1.0,2.0],"dir":[1.0,0.0],"owner":2}]"""), 777L)
        assertEquals(777L, s.bullets[0].recvMs)
    }

    @Test fun teamAndMeleeFieldsParse() {
        val s = GameSnapshot()
        s.apply(msg("player_update", """{"2":{"pos":[1.0,2.0],"health":100,"team_id":3,"melee_attacking":true,"melee_direction":45.0}}"""), 1L)
        val p = s.players[2]!!
        assertEquals(3, p.teamId)
        assertTrue(p.meleeAttacking)
        assertEquals(45.0, p.meleeDirection, 1e-9)
    }

    @Test fun itemPickupBroadcastIsRecorded() {
        val s = GameSnapshot()
        s.apply(msg("item_pickup", """{"player_id":2,"item_id":11,"effect":{}}"""), 900L)
        assertEquals(11, s.lastPickupItemId)
        assertEquals(900L, s.lastPickupMs)
    }
}
