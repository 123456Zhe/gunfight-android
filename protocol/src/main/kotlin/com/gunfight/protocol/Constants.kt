package com.gunfight.protocol

/** Protocol constants (mirror reference/settings.json defaults). */
object Net {
    const val DEFAULT_PORT = 5555
    const val BUFFER_SIZE = 65536
    const val HEARTBEAT_INTERVAL_SEC = 1.0
    const val CLIENT_TIMEOUT_SEC = 5.0
    const val CONNECTION_TIMEOUT_SEC = 10.0
    const val MAX_PLAYERS = 10
    const val BROADCAST_HZ = 20.0
}

object Game {
    const val ROOM_SIZE = 600.0
    const val MAP_SIZE = ROOM_SIZE * 3 // 1800 x 1800 px, y-down
    const val PLAYER_RADIUS = 20.0
    const val BULLET_RADIUS = 5.0
    const val BULLET_SPEED = 800.0
}
