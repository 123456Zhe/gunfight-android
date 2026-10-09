package com.gunfight.protocol

/** Raw packet kinds (before JSON envelope dispatch). */
sealed interface Packet {
    data class Probe(val text: String = "server_probe") : Packet
    data class ServerInfo(val id: String, val name: String, val players: Int, val maxPlayers: Int, val version: String) : Packet
    /** fields = whole JSON object; data = fields["data"] (object, list, or null). */
    data class Message(val type: String, val fields: Map<String, Any?>, val data: Any?) : Packet
    data object Invalid : Packet
}

const val PROBE_TEXT = "server_probe"
const val INFO_PREFIX = "server_info:"

/** Outgoing C->S builders (mirrors reference/network.py client side). */
fun buildProbe(): String = PROBE_TEXT

fun buildConnectRequest(playerName: String): String =
    Json.stringify(mapOf("type" to "connect_request", "player_name" to playerName))

fun buildHeartbeat(playerId: Int, timestampSec: Double): String =
    Json.stringify(mapOf("type" to "heartbeat", "data" to mapOf("player_id" to playerId, "timestamp" to timestampSec)))

/** C->S player state report (~20Hz). Server is authoritative for health/ammo/etc. */
fun buildPlayerUpdate(
    pid: Int, x: Double, y: Double, angleDeg: Double,
    shooting: Boolean, isReloading: Boolean, name: String
): String = Json.stringify(mapOf(
    "type" to "player_update",
    "data" to mapOf(pid.toString() to mapOf(
        "pos" to listOf(x, y),
        "angle" to angleDeg,
        "shooting" to shooting,
        "is_reloading" to isReloading,
        "name" to name,
        "melee_attacking" to false,
        "weapon_type" to "gun",
        "is_aiming" to false
    ))
))

/** C->S fire request. seq dedups retransmits server-side. */
fun buildFireRequest(pid: Int, x: Double, y: Double, dx: Double, dy: Double, seq: Int): String =
    Json.stringify(mapOf(
        "type" to "request_bullet",
        "data" to mapOf("pos" to listOf(x, y), "dir" to listOf(dx, dy), "owner" to pid, "seq" to seq)
    ))

/** C->S grenade throw request. */
fun buildGrenadeRequest(pid: Int, x: Double, y: Double, dx: Double, dy: Double, seq: Int): String =
    Json.stringify(mapOf(
        "type" to "request_grenade",
        "data" to mapOf("pos" to listOf(x, y), "dir" to listOf(dx, dy), "owner" to pid, "seq" to seq)
    ))

/** C->S item pickup request. Server validates distance and computes the effect. */
fun buildItemPickup(pid: Int, itemId: Int): String =
    Json.stringify(mapOf(
        "type" to "item_pickup",
        "data" to mapOf("player_id" to pid, "item_id" to itemId)
    ))

/** C->S door state sync (door_id = game_map.doors index). */
fun buildDoorUpdate(doorId: Int, progress: Double, version: Int): String =
    Json.stringify(mapOf(
        "type" to "door_update",
        "data" to mapOf(
            "door_id" to doorId,
            "state" to mapOf(
                "is_open" to (kotlin.math.abs(progress) >= 1.0),
                "is_opening" to false,
                "is_closing" to false,
                "animation_progress" to progress,
                "swing_velocity" to 0.0,
                "version" to version
            )
        )
    ))

/** Parse one UDP datagram. Never throws: garbage -> Packet.Invalid. */
@Suppress("UNCHECKED_CAST")
fun parsePacket(text: String): Packet {
    if (text == PROBE_TEXT) return Packet.Probe()
    if (text.startsWith(INFO_PREFIX)) {
        return try {
            val m = Json.parse(text.removePrefix(INFO_PREFIX)) as? Map<String, Any?> ?: return Packet.Invalid
            Packet.ServerInfo(
                id = m.str("id"), name = m.str("name"),
                players = m.int("players"), maxPlayers = m.int("max_players"),
                version = m.str("version", "1.0")
            )
        } catch (_: Exception) {
            Packet.Invalid
        }
    }
    return try {
        val m = Json.parse(text) as? Map<String, Any?> ?: return Packet.Invalid
        val type = m["type"] as? String ?: return Packet.Invalid
        Packet.Message(type, m, m["data"])
    } catch (_: Exception) {
        Packet.Invalid
    }
}
