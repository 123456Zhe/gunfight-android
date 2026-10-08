package com.gunfight.protocol

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
    val shooting: Boolean = false
) {
    companion object {
        fun from(id: Int, m: Map<String, Any?>): PlayerState {
            val pos = m["pos"] as? List<*> ?: listOf(0.0, 0.0)
            return PlayerState(
                id = id,
                posX = num(pos.getOrNull(0)), posY = num(pos.getOrNull(1)),
                angle = m.dbl("angle"), health = m.int("health", 100),
                ammo = m.int("ammo"), armor = m.int("armor"),
                name = m.str("name", "P$id"), isDead = m.bool("is_dead"),
                weaponType = m.str("weapon_type", "gun"),
                grenades = m.int("grenades"),
                isReloading = m.bool("is_reloading"), shooting = m.bool("shooting")
            )
        }
    }
}

data class BulletState(val id: Int, val x: Double, val y: Double, val dx: Double, val dy: Double, val owner: Int) {
    companion object {
        fun from(m: Map<String, Any?>): BulletState? {
            val pos = m["pos"] as? List<*> ?: return null
            val dir = m["dir"] as? List<*> ?: return null
            return BulletState(m.int("id"), num(pos.getOrNull(0)), num(pos.getOrNull(1)),
                num(dir.getOrNull(0)), num(dir.getOrNull(1)), m.int("owner"))
        }
    }
}

data class GrenadeState(val id: Int, val x: Double, val y: Double, val ownerId: Int, val exploded: Boolean) {
    companion object {
        fun from(m: Map<String, Any?>): GrenadeState? {
            val pos = m["pos"] as? List<*> ?: return null
            return GrenadeState(m.int("id"), num(pos.getOrNull(0)), num(pos.getOrNull(1)),
                m.int("owner_id"), m.bool("exploded"))
        }
    }
}

data class ItemState(val id: Int, val type: String, val x: Double, val y: Double, val active: Boolean) {
    companion object {
        fun from(m: Map<String, Any?>): ItemState? {
            val pos = m["pos"] as? List<*> ?: return null
            return ItemState(m.int("id"), m.str("type"), num(pos.getOrNull(0)), num(pos.getOrNull(1)),
                m["is_active"] as? Boolean ?: true)
        }
    }
}

data class CombatEvent(
    val kind: String, val attackerId: Int, val targetId: Int,
    val attackerName: String, val targetName: String,
    val damage: Double, val damageType: String
) {
    companion object {
        fun from(m: Map<String, Any?>): CombatEvent = CombatEvent(
            m.str("kind"), m.int("attacker_id"), m.int("target_id"),
            m.str("attacker_name"), m.str("target_name"),
            m.dbl("damage"), m.str("damage_type")
        )
    }
}

/** Local mirror of authoritative server state (spec: client is renderer only).
 * Written by the network thread at 20Hz, read by the UI thread: all
 * collections are concurrent so iteration never throws CME. */
class GameSnapshot {
    val players = java.util.concurrent.ConcurrentHashMap<Int, PlayerState>()
    val bullets = java.util.concurrent.CopyOnWriteArrayList<BulletState>()
    val grenades = java.util.concurrent.CopyOnWriteArrayList<GrenadeState>()
    val items = java.util.concurrent.ConcurrentHashMap<Int, ItemState>()
    val doors = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()
    val kills = java.util.concurrent.CopyOnWriteArrayList<CombatEvent>() // kill broadcasts (all clients)
    @Volatile var lastHit: CombatEvent? = null // hit unicast (we are attacker)
        private set

    @Suppress("UNCHECKED_CAST")
    fun apply(msg: Packet.Message) {
        val map = msg.data as? Map<String, Any?> ?: emptyMap()
        when (msg.type) {
            "init_players" -> {
                players.clear()
                putPlayers(map)
            }
            "player_update" -> putPlayers(map) // full broadcast: upsert each entry
            "bullets_update" -> {
                // spec: data IS the list; accept {"items"-style wrappers too
                bullets.clear()
                val list = (msg.data as? List<*>) ?: firstList(map)
                for (e in list) (e as? Map<String, Any?>)?.let { BulletState.from(it)?.let(bullets::add) }
            }
            "grenade_update" -> {
                grenades.clear()
                val list = (msg.data as? List<*>) ?: firstList(map)
                for (e in list) (e as? Map<String, Any?>)?.let { GrenadeState.from(it)?.let(grenades::add) }
            }
            "door_update" -> {
                val id = map.int("door_id").toString() // JSON numbers arrive as Double; "0" not "0.0"
                (map["state"] as? Map<String, Any?>)?.let { doors[id] = it }
            }
            "item_update" -> {
                items.clear()
                val list = firstList(map)
                for (e in list) (e as? Map<String, Any?>)?.let { ItemState.from(it)?.let { items[it.id] = it } }
            }
            "combat_feedback" -> {
                val ev = CombatEvent.from(map)
                if (ev.kind == "kill") kills.add(ev) else lastHit = ev
            }
            "respawn" -> {
                val pid = map.int("player_id", -1)
                players[pid]?.let { p ->
                    val pos = map["pos"] as? List<*> ?: listOf(p.posX, p.posY)
                    players[pid] = p.copy(posX = num(pos.getOrNull(0)), posY = num(pos.getOrNull(1)),
                        health = map.int("health", 100), isDead = false)
                }
            }
        }
    }

    private fun putPlayers(data: Map<String, Any?>) {
        for ((k, v) in data) {
            val pid = k.toIntOrNull() ?: continue // JSON keys arrive as strings (spec pitfall #2)
            (v as? Map<String, Any?>)?.let { players[pid] = PlayerState.from(pid, it) }
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun firstList(data: Map<String, Any?>): List<Any?> {
            for (v in data.values) if (v is List<*>) return v as List<Any?>
            return emptyList()
        }
    }
}
