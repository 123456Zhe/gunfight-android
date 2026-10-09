package com.gunfight.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Blocking UDP client (JVM + Android). Callbacks fire on the receiver thread. */
class GameClient(
    serverHost: String,
    serverPort: Int = Net.DEFAULT_PORT,
    val playerName: String,
    private val listener: Listener = Listener(),
    heartbeatIntervalSec: Double = Net.HEARTBEAT_INTERVAL_SEC,
    private val clientTimeoutSec: Double = Net.CLIENT_TIMEOUT_SEC
) {
    open class Listener {
        open fun onPacket(packet: Packet) {}
        open fun onTimeout() {}
        open fun onKicked(reason: String) {}
    }

    val serverAddress = InetSocketAddress(serverHost, serverPort)
    val snapshot = GameSnapshot()
    val handshake = Handshake()
    val socket = DatagramSocket()

    @Volatile var connected = false
        private set
    @Volatile var lastResponseMs: Long = 0
        private set

    private val running = AtomicBoolean(false)
    private var recvThread: Thread? = null
    private var hbThread: Thread? = null
    private val hbIntervalMs = (heartbeatIntervalSec * 1000).toLong()
    /** Debug counters: packet type -> received count (wireshark-style HUD). */
    val packetCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        socket.soTimeout = 1000
    }

    fun isTimedOut(nowMs: Long = System.currentTimeMillis()): Boolean =
        connected && nowMs - lastResponseMs > clientTimeoutSec * 1000

    /** Full blocking handshake: probe -> connect_request -> wait connect_response. */
    fun connect(block: (String) -> Unit = {}): Boolean {
        running.set(true)
        startReceiver()
        sendRaw(buildProbe())
        handshake.onProbeSent()
        val deadline = System.currentTimeMillis() + (Net.CONNECTION_TIMEOUT_SEC * 1000).toLong()
        var connectSent = false
        var lastSent = 0L
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            // Probe is best-effort discovery; connect_request goes out immediately and retries.
            if (!connectSent || now - lastSent > 2000) {
                sendRaw(buildConnectRequest(playerName))
                handshake.onConnectSent()
                connectSent = true
                lastSent = now
            }
            when (handshake.state) {
                Handshake.State.CONNECTED -> {
                    connected = true
                    snapshot.localPlayerId = handshake.clientId
                    lastResponseMs = System.currentTimeMillis()
                    startHeartbeat()
                    return true
                }
                Handshake.State.ROOM_FULL, Handshake.State.FAILED -> {
                    block(handshake.error ?: "rejected")
                    close()
                    return false
                }
                else -> Thread.sleep(50)
            }
        }
        handshake.checkTimeout()
        block(handshake.error ?: "connection timeout")
        close()
        return false
    }

    private fun startReceiver() {
        recvThread = thread(name = "gunfight-recv", isDaemon = true) {
            val buf = ByteArray(Net.BUFFER_SIZE)
            while (running.get()) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    // Only accept packets from the connected server address (spec 2.3).
                    if (p.socketAddress != serverAddress) continue
                    lastResponseMs = System.currentTimeMillis()
                    val text = String(p.data, 0, p.length, Charsets.UTF_8)
                    val packet = parsePacket(text)
                    if (packet is Packet.Message) {
                        packetCounts.merge(packet.type, 1, Int::plus)
                        if (!connected) handshake.onMessage(packet)
                        // State packets apply even before connect() flips the flag:
                        // the server's init burst arrives microseconds after
                        // connect_response, always beating the 50ms connect() poll.
                        if (packet.type != "heartbeat_response") snapshot.apply(packet)
                    }
                    if (packet is Packet.Message && packet.type == "kick") {
                        val reason = (packet.data as? Map<*, *>)?.get("reason") as? String
                        connected = false
                        listener.onKicked(reason ?: "被服务器踢出")
                        close()
                        break
                    }
                    if (packet is Packet.ServerInfo) handshake.onServerInfo(packet)
                    listener.onPacket(packet)
                } catch (_: SocketTimeoutException) {
                    if (isTimedOut()) {
                        connected = false
                        listener.onTimeout()
                    }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    private fun startHeartbeat() {
        hbThread = thread(name = "gunfight-hb", isDaemon = true) {
            while (running.get() && connected) {
                try {
                    sendRaw(buildHeartbeat(handshake.clientId, System.currentTimeMillis() / 1000.0))
                } catch (_: Exception) {
                }
                Thread.sleep(hbIntervalMs)
            }
        }
    }

    fun sendRaw(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(b, b.size, serverAddress))
    }

    // ---- player inputs (Phase 2: playable client) ----
    private var fireSeq = 0
    private var grenadeSeq = 0

    fun sendPlayerUpdate(
        x: Double, y: Double, angleDeg: Double, shooting: Boolean, isReloading: Boolean,
        meleeAttacking: Boolean = false, meleeDirection: Double = 0.0
    ) {
        val pid = handshake.clientId
        if (pid < 0) return
        try {
            sendRaw(
                buildPlayerUpdate(
                    pid, x, y, angleDeg, shooting, isReloading, playerName,
                    meleeAttacking, meleeDirection,
                    if (meleeAttacking) "melee" else "gun"
                )
            )
        } catch (_: Exception) {
        }
    }

    fun sendMelee(directionDeg: Double, targets: List<Int>, isHeavy: Boolean) {
        val pid = handshake.clientId
        if (pid < 0) return
        try {
            sendRaw(buildMeleeAttack(pid, directionDeg, targets, isHeavy))
        } catch (_: Exception) {
        }
    }

    fun sendChat(message: String, isTeamChat: Boolean = false) {
        val pid = handshake.clientId
        if (pid < 0 || message.isEmpty()) return
        try {
            sendRaw(buildChatMessage(pid, playerName, message, isTeamChat))
        } catch (_: Exception) {
        }
    }

    fun sendFire(x: Double, y: Double, dx: Double, dy: Double) {
        val pid = handshake.clientId
        if (pid < 0) return
        try {
            sendRaw(buildFireRequest(pid, x, y, dx, dy, ++fireSeq))
        } catch (_: Exception) {
        }
    }

    fun sendGrenade(x: Double, y: Double, dx: Double, dy: Double) {
        val pid = handshake.clientId
        if (pid < 0) return
        try {
            sendRaw(buildGrenadeRequest(pid, x, y, dx, dy, ++grenadeSeq))
        } catch (_: Exception) {
        }
    }

    private val doorVersions = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** map.py applies a door state only when version strictly increases, so seed from the
     * last broadcast version: a stale local counter would be shadowed by other clients. */
    fun sendDoorUpdate(doorId: Int, progress: Double, serverVersion: Int = 0) {
        val next = maxOf(serverVersion, doorVersions[doorId] ?: 0) + 1
        doorVersions[doorId] = next
        try {
            sendRaw(buildDoorUpdate(doorId, progress, next))
        } catch (_: Exception) {
        }
    }

    fun sendItemPickup(itemId: Int) {
        val pid = handshake.clientId
        if (pid < 0) return
        try {
            sendRaw(buildItemPickup(pid, itemId))
        } catch (_: Exception) {
        }
    }

    fun close() {
        running.set(false)
        connected = false
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
