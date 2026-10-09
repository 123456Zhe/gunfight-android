package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * End-to-end client behaviour against the real Python server, using a fake
 * door/item/player world built by refserver/helper_server.py
 * (GUNFIGHT_HELPER_WORLD=1): item patches and the pickup round trip, per-door
 * version seeding, melee damage and chat broadcast.
 *
 * Skips unless GUNFIGHT_REFERENCE_NETWORK (or reference/network.py) is available.
 */
class PickupInteropTest {
    private fun helperScript(): File? =
        javaClass.classLoader.getResource("refserver/helper_server.py")?.let { File(it.toURI()) }

    private fun referenceNetworkPy(): File? {
        System.getenv("GUNFIGHT_REFERENCE_NETWORK")?.let { if (File(it).isFile) return File(it) }
        val helper = helperScript() ?: return null
        val repoRoot = helper.parentFile.parentFile.parentFile.parentFile.parentFile
        return File(repoRoot, "reference/network.py").takeIf { it.isFile }
    }

    private fun pythonAvailable(): Boolean = try {
        val p = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS)
        p.exitValue() == 0
    } catch (_: IOException) {
        false
    } catch (_: InterruptedException) {
        false
    }

    private fun waitFor(deadlineMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(50)
        }
        fail("timeout waiting for " + what)
    }

    /** Starts the world helper and returns (process, log, port). */
    private fun launchWorldHelper(): Triple<Process, CopyOnWriteArrayList<String>, Int> {
        val helper = helperScript()!!
        val proc = ProcessBuilder("python3", helper.absolutePath)
            .directory(helper.parentFile)
            .redirectErrorStream(true)
            .apply {
                environment()["GUNFIGHT_REFERENCE_NETWORK"] = referenceNetworkPy()!!.absolutePath
                environment()["GUNFIGHT_HELPER_WORLD"] = "1"
            }
            .start()
        val log = CopyOnWriteArrayList<String>()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { log.add(it) }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        var port = -1
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end && port <= 0) {
            for (line in log) {
                Regex("READY port=(\\d+)").find(line)?.let { port = it.groupValues[1].toInt() }
            }
            if (port <= 0) Thread.sleep(50)
        }
        assertTrue("helper never reported a port: " + log.joinToString(" | "), port > 0)
        return Triple(proc, log, port)
    }

    @Test(timeout = 90000) fun pickupAndDoorPush() {
        Assume.assumeTrue("python3 not on PATH", pythonAvailable())
        Assume.assumeTrue("refserver/helper_server.py missing", helperScript() != null)
        Assume.assumeTrue(
            "no reference server: copy zd-2d-gunfight into reference/ or set GUNFIGHT_REFERENCE_NETWORK",
            referenceNetworkPy() != null
        )
        val (proc, log, port) = launchWorldHelper()
        try {
            val client = GameClient("127.0.0.1", port, "KotlinPickup")
            try {
                assertTrue("handshake with reference server failed", client.connect())
                val snap = client.snapshot

                waitFor(8000, "item_update broadcast") { snap.items.isNotEmpty() }
                assertTrue("item must start active", snap.items[1]!!.active)

                // pickup request -> server flips it inactive and broadcasts item_pickup
                val end = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < end && snap.items[1]?.active != false) {
                    client.sendItemPickup(1)
                    Thread.sleep(200)
                }
                assertFalse("server did not flip the item inactive", snap.items[1]!!.active)
                assertEquals("item_pickup broadcast missing", 1, snap.lastPickupItemId)

                client.sendDoorUpdate(0, 1.0, 5)
                waitFor(3000, "near door echo") { snap.doors["0"] != null }
                assertEquals(
                    "near door push must be echoed with serverVersion+1",
                    6, (snap.doors["0"]?.get("version") as? Number)?.toInt()
                )

                client.sendDoorUpdate(0, -1.0, 6)
                waitFor(3000, "second door echo") {
                    ((snap.doors["0"]?.get("version") as? Number)?.toInt() ?: 0) >= 7
                }
                assertEquals(7, (snap.doors["0"]?.get("version") as? Number)?.toInt())

                client.sendDoorUpdate(1, 1.0, 0)
                Thread.sleep(800)
                assertNull("a door we are not standing next to must be rejected", snap.doors["1"])
            } finally {
                client.close()
            }
        } finally {
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
            proc.destroyForcibly()
        }
    }

    @Test(timeout = 90000) fun meleeAndChatRoundTrip() {
        Assume.assumeTrue("python3 not on PATH", pythonAvailable())
        Assume.assumeTrue("refserver/helper_server.py missing", helperScript() != null)
        Assume.assumeTrue(
            "no reference server: copy zd-2d-gunfight into reference/ or set GUNFIGHT_REFERENCE_NETWORK",
            referenceNetworkPy() != null
        )
        val (proc, log, port) = launchWorldHelper()
        try {
            val client = GameClient("127.0.0.1", port, "KotlinMelee")
            try {
                assertTrue("handshake with reference server failed", client.connect())
                val snap = client.snapshot
                val me = client.handshake.clientId

                // the fake world puts a dummy player 30px to the +x of our spawn
                waitFor(8000, "dummy player in snapshot") { snap.players[3] != null }
                val mePos = snap.players[me]!!
                val dummy = snap.players[3]!!
                assertEquals("dummy must start at full health", 100, dummy.health)
                assertTrue("dummy must be within melee reach", dummy.posX - mePos.posX in 1.0..80.0)

                client.sendMelee(0.0, listOf(3), isHeavy = false)
                waitFor(4000, "melee damage on the dummy") { (snap.players[3]?.health ?: 100) < 100 }
                assertEquals("melee damage comes from settings (40)", 60, snap.players[3]!!.health)
                waitFor(2000, "hit combat feedback") { snap.lastHit?.targetId == 3 }

                client.sendChat("hello from kotlin")
                waitFor(4000, "chat broadcast") { snap.chat.any { it.text == "hello from kotlin" } }
            } finally {
                client.close()
            }
        } finally {
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
            proc.destroyForcibly()
        }
    }
}
