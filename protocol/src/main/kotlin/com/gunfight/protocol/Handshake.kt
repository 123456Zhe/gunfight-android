package com.gunfight.protocol

/** Connection state machine: probe -> connect_request -> connect_response -> connected. */
class Handshake(
    private val connectionTimeoutSec: Double = Net.CONNECTION_TIMEOUT_SEC,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    enum class State { IDLE, PROBE_SENT, CONNECT_SENT, CONNECTED, ROOM_FULL, FAILED }

    var state: State = State.IDLE
        private set
    var clientId: Int = -1
        private set
    var serverName: String = ""
        private set
    var serverInfo: Packet.ServerInfo? = null
        private set
    var error: String? = null
        private set

    private val startMs = nowMs()

    fun onProbeSent() {
        if (state == State.IDLE) state = State.PROBE_SENT
    }

    fun onServerInfo(info: Packet.ServerInfo) {
        serverInfo = info
    }

    fun onConnectSent() {
        if (state == State.PROBE_SENT || state == State.IDLE) state = State.CONNECT_SENT
    }

    /** Returns true when this packet completed the handshake. */
    fun onMessage(msg: Packet.Message): Boolean {
        when (msg.type) {
            "connect_response" -> {
                // NOTE: server puts client_id/server_name top-level, not under data
                clientId = msg.fields.int("client_id", -1)
                serverName = msg.fields.str("server_name")
                state = State.CONNECTED
                return true
            }
            "room_full" -> {
                state = State.ROOM_FULL
                error = "room full"
                return true
            }
            "kick" -> {
                @Suppress("UNCHECKED_CAST")
                val m = msg.data as? Map<String, Any?> ?: emptyMap()
                state = State.FAILED
                error = m.str("reason", msg.fields.str("reason", "kicked"))
                return true
            }
        }
        return false
    }

    fun checkTimeout(): Boolean {
        if (state == State.CONNECTED || state == State.ROOM_FULL || state == State.FAILED) return false
        if ((nowMs() - startMs) / 1000.0 > connectionTimeoutSec) {
            state = State.FAILED
            error = "connection timeout"
            return true
        }
        return false
    }
}
