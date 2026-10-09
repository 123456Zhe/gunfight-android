package com.gunfight.protocol

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Angle convention (spec section 5): degrees, 0 = +x, 90 = up, CCW positive, y-down. */
object Angles {
    const val DEG = 180.0 / Math.PI

    /** Matches Python math.degrees(math.atan2(-dy, dx)); normalizes -180 -> 180. */
    fun of(dx: Double, dy: Double): Double {
        val a = atan2(-dy, dx) * DEG
        return if (a <= -180.0) a + 360.0 else a
    }

    fun dir(angleDeg: Double): Pair<Double, Double> {
        val r = angleDeg / DEG
        return cos(r) to -sin(r) // y-down: positive angle goes up
    }

    fun normalize(a: Double): Double {
        var x = a % 360.0
        if (x < 0) x += 360.0
        return x
    }

    /** Minimum angular distance, mirrors Python utils.angle_difference. */
    fun diff(a: Double, b: Double): Double {
        val d = abs(normalize(a) - normalize(b))
        return minOf(d, 360.0 - d)
    }
}

data class PlayerState(
    val id: Int,
    val posX: Double, val posY: Double,
    val angle: Double,
    val health: Int, val ammo: Int, val armor: Int,
    val name: String,
    val isDead: Boolean,
    val weaponType: String,
    val grenades: Int,
    val isReloading: Boolean = false,
    val shooting: Boolean = false,
    val respawnTime: Double = 0.0,
    val teamId: Int? = null,
    val meleeAttacking: Boolean = false,
    val meleeDirection: Double = 0.0
) {
    companion object {
        fun from(id: Int, m: Map<String, Any?>): PlayerState {
            val pos = m["pos"] as? List<*> ?: listOf(0.0, 0.0)
            return PlayerState(
                id = id,
                posX = num(pos.getOrNull(0)), posY = num(pos.getOrNull(1)),
                angle = m.dbl("angle"), health = m.int("health", 100),
                ammo = m.int("ammo"), armor = m.int("armor"),
                name = m.str("name", "P" + id), isDead = m.bool("is_dead"),
                weaponType = m.str("weapon_type", "gun"),
                grenades = m.int("grenades"),
                isReloading = m.bool("is_reloading"), shooting = m.bool("shooting"),
                respawnTime = m.dbl("respawn_time"),
                teamId = (m["team_id"] as? Number)?.toInt(),
                meleeAttacking = m.bool("melee_attacking"),
                meleeDirection = m.dbl("melee_direction")
            )
        }
    }
}

data class BulletState(
    val id: Int, val x: Double, val y: Double,
    val dx: Double, val dy: Double, val owner: Int,
    val recvMs: Long = 0L
) {
    companion object {
        fun from(m: Map<String, Any?>, nowMs: Long = 0L): BulletState? {
            val pos = m["pos"] as? List<*> ?: return null
            val dir = m["dir"] as? List<*> ?: return null
            return BulletState(
                m.int("id"), num(pos.getOrNull(0)), num(pos.getOrNull(1)),
                num(dir.getOrNull(0)), num(dir.getOrNull(1)), m.int("owner"), nowMs
            )
        }
    }
}

data class GrenadeState(val id: Int, val x: Double, val y: Double, val ownerId: Int, val exploded: Boolean) {
    companion object {
        fun from(m: Map<String, Any?>): GrenadeState? {
            val pos = m["pos"] as? List<*> ?: return null
            return GrenadeState(
                m.int("id"), num(pos.getOrNull(0)), num(pos.getOrNull(1)),
                m.int("owner_id"), m.bool("exploded")
            )
        }
    }
}

data class ItemState(
    val id: Int, val type: String, val x: Double, val y: Double,
    val active: Boolean, val respawnRemaining: Double = 0.0
) {
    companion object {
        fun from(m: Map<String, Any?>): ItemState? {
            val pos = m["pos"] as? List<*> ?: return null
            return ItemState(
                m.int("id"), m.str("type"), num(pos.getOrNull(0)), num(pos.getOrNull(1)),
                m["is_active"] as? Boolean ?: true, m.dbl("respawn_time_remaining")
            )
        }
    }
}

data class CombatEvent(
    val kind: String, val attackerId: Int, val targetId: Int,
    val attackerName: String, val targetName: String,
    val damage: Double, val damageType: String,
    val targetPosX: Double? = null, val targetPosY: Double? = null,
    val recvMs: Long = 0L
) {
    companion object {
        fun from(m: Map<String, Any?>, nowMs: Long = 0L): CombatEvent {
            val tp = m["target_pos"] as? List<*>
            return CombatEvent(
                m.str("kind"), m.int("attacker_id"), m.int("target_id"),
                m.str("attacker_name"), m.str("target_name"),
                m.dbl("damage"), m.str("damage_type"),
                tp?.getOrNull(0)?.let { num(it) }, tp?.getOrNull(1)?.let { num(it) }, nowMs
            )
        }
    }
}

/** Server-side grenade detonation, inferred from grenade_update transitions. */
data class Explosion(val x: Double, val y: Double, val recvMs: Long)

/** Broadcast chat/system line (player_id == 0 is a server system message). */
data class ChatLine(val playerId: Int, val name: String, val text: String, val recvMs: Long)

/** Local mirror of authoritative server state (spec: client is renderer only).
 * Written by the network thread at 20Hz, read by the UI thread: all
 * collections are concurrent so iteration never throws CME. */
class GameSnapshot {
    val players = ConcurrentHashMap<Int, PlayerState>()
    val bullets = CopyOnWriteArrayList<BulletState>()
    val grenades = CopyOnWriteArrayList<GrenadeState>()
    val items = ConcurrentHashMap<Int, ItemState>()
    val doors = ConcurrentHashMap<String, Map<String, Any?>>()
    val kills = CopyOnWriteArrayList<CombatEvent>()
    val explosions = CopyOnWriteArrayList<Explosion>()
    val chat = CopyOnWriteArrayList<ChatLine>()

    /** Last time each pid appeared in a player_update broadcast (ghost eviction). */
    val playerLastSeen = ConcurrentHashMap<Int, Long>()

    /** Never evicted: the local player entry must survive packet loss. */
    @Volatile var localPlayerId: Int = -1

    /** Last hit unicast (we are the attacker). */
    @Volatile var lastHit: CombatEvent? = null
        private set

    /** Last item_pickup broadcast, so the UI can clear its pending request. */
    @Volatile var lastPickupItemId: Int = -1
        private set
    @Volatile var lastPickupMs: Long = 0L
        private set

    private val lastGrenadePos = HashMap<Int, Pair<Double, Double>>()
    private val reportedExplosions = HashSet<Int>()

    private fun addKill(ev: CombatEvent) {
        kills.add(ev)
        while (kills.size > MAX_KILL_FEED) kills.removeAt(0)
    }

    private fun addChat(line: ChatLine) {
        chat.add(line)
        while (chat.size > MAX_CHAT_LINES) chat.removeAt(0)
    }

    private fun addExplosion(id: Int, x: Double, y: Double, nowMs: Long) {
        if (!reportedExplosions.add(id)) return
        explosions.add(Explosion(x, y, nowMs))
        while (explosions.size > MAX_EXPLOSIONS) explosions.removeAt(0)
    }

    /** Drop players that stopped being broadcast (disconnected) after ttlMs. */
    fun pruneStalePlayers(nowMs: Long, ttlMs: Long = 2000): List<Int> {
        val gone = ArrayList<Int>()
        for ((pid, seen) in playerLastSeen) {
            if (pid == localPlayerId) continue
            if (nowMs - seen > ttlMs) gone.add(pid)
        }
        for (pid in gone) {
            playerLastSeen.remove(pid)
            players.remove(pid)
        }
        return gone
    }

    @Suppress("UNCHECKED_CAST")
    fun apply(msg: Packet.Message, nowMs: Long = System.currentTimeMillis()) {
        val map = msg.data as? Map<String, Any?> ?: emptyMap()
        when (msg.type) {
            "init_players" -> {
                players.clear()
                playerLastSeen.clear()
                putPlayers(map, nowMs)
            }
            "player_update" -> putPlayers(map, nowMs) // chunked full broadcast: upsert each entry
            "bullets_update" -> {
                bullets.clear()
                val list = (msg.data as? List<*>) ?: firstList(map)
                for (e in list) (e as? Map<String, Any?>)?.let { BulletState.from(it, nowMs)?.let(bullets::add) }
            }
            "grenade_update" -> {
                val list = (msg.data as? List<*>) ?: firstList(map)
                val states = ArrayList<GrenadeState>()
                for (e in list) (e as? Map<String, Any?>)?.let { GrenadeState.from(it)?.let(states::add) }
                // a grenade that vanished from the authoritative list just detonated
                for ((gid, pos) in lastGrenadePos) {
                    if (states.none { it.id == gid }) addExplosion(gid, pos.first, pos.second, nowMs)
                }
                for (g in states) {
                    if (g.exploded) addExplosion(g.id, g.x, g.y, nowMs)
                    lastGrenadePos[g.id] = g.x to g.y
                }
                grenades.clear()
                grenades.addAll(states)
            }
            "door_update" -> {
                val id = map.int("door_id").toString() // JSON numbers arrive as Double; "0" not "0.0"
                (map["state"] as? Map<String, Any?>)?.let { doors[id] = it }
            }
            "item_update" -> {
                items.clear()
                val list = firstList(map)
                for (e in list) (e as? Map<String, Any?>)?.let {
                    ItemState.from(it)?.let { s -> items[s.id] = s }
                }
            }
            "combat_feedback" -> {
                val ev = CombatEvent.from(map, nowMs)
                if (ev.kind == "kill") addKill(ev) else lastHit = ev
            }
            "item_pickup" -> {
                lastPickupItemId = map.int("item_id", -1)
                lastPickupMs = nowMs
            }
            "chat_message" -> addChat(chatLine(map, nowMs))
            "chat_history" -> {
                for (e in firstList(map)) (e as? Map<String, Any?>)?.let { addChat(chatLine(it, nowMs)) }
            }
            "respawn" -> {
                val pid = map.int("player_id", -1)
                playerLastSeen[pid] = nowMs
                players[pid]?.let { p ->
                    val pos = map["pos"] as? List<*> ?: listOf(p.posX, p.posY)
                    players[pid] = p.copy(
                        posX = num(pos.getOrNull(0)), posY = num(pos.getOrNull(1)),
                        health = map.int("health", 100), isDead = false, respawnTime = 0.0
                    )
                }
            }
        }
    }

    private fun chatLine(map: Map<String, Any?>, nowMs: Long) = ChatLine(
        map.int("player_id"), map.str("player_name"),
        map.str("message"), nowMs
    )

    private fun putPlayers(data: Map<String, Any?>, nowMs: Long) {
        for ((k, v) in data) {
            val pid = k.toIntOrNull() ?: continue // JSON keys arrive as strings (spec pitfall #2)
            (v as? Map<String, Any?>)?.let {
                players[pid] = PlayerState.from(pid, it)
                playerLastSeen[pid] = nowMs
            }
        }
    }

    companion object {
        const val MAX_KILL_FEED = 40
        const val MAX_CHAT_LINES = 20
        const val MAX_EXPLOSIONS = 12

        @Suppress("UNCHECKED_CAST")
        fun firstList(data: Map<String, Any?>): List<Any?> {
            for (v in data.values) if (v is List<*>) return v as List<Any?>
            return emptyList()
        }
    }
}
