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
 * door/item world built by refserver/helper_server.py (GUNFIGHT_HELPER_WORLD=1):
 * item_update parsing, the item_pickup request round trip, and per-door version
 * seeding for door pushes.
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

    @Test(timeout = 90000) fun pickupAndDoorPush() {
        val helper = helperScript()
        val networkPy = referenceNetworkPy()
        Assume.assumeTrue("python3 not on PATH", pythonAvailable())
        Assume.assumeTrue("refserver/helper_server.py missing", helper != null)
        Assume.assumeTrue(
            "no reference server: copy zd-2d-gunfight into reference/ or set GUNFIGHT_REFERENCE_NETWORK",
            networkPy != null
        )

        val proc = ProcessBuilder("python3", helper!!.absolutePath)
            .directory(helper.parentFile)
            .redirectErrorStream(true)
            .apply {
                environment()["GUNFIGHT_REFERENCE_NETWORK"] = networkPy!!.absolutePath
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
        try {
            var port = -1
            val readyBy = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < readyBy && port <= 0) {
                for (line in log) {
                    Regex("READY port=(\\d+)").find(line)?.let {
                        port = it.groupValues[1].toInt()
                    }
                }
                if (port <= 0) Thread.sleep(50)
            }
            assertTrue("helper never reported a port: " + log.joinToString(" | "), port > 0)

            val client = GameClient("127.0.0.1", port, "KotlinPickup")
            try {
                assertTrue("handshake with reference server failed", client.connect())
                val snap = client.snapshot

                var t = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < t && snap.items.isEmpty()) {
                    client.sendPlayerUpdate(snap.players[client.handshake.clientId]?.posX ?: 0.0,
                        snap.players[client.handshake.clientId]?.posY ?: 0.0, 0.0, false, false)
                    Thread.sleep(50)
                }
                assertTrue("no item_update broadcast received", snap.items.isNotEmpty())
                assertTrue("item must start active", snap.items[1]!!.active)

                t = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < t && snap.items[1]?.active != false) {
                    client.sendItemPickup(1)
                    Thread.sleep(200)
                }
                assertFalse("server did not flip the item inactive", snap.items[1]!!.active)
                assertEquals("item_pickup broadcast missing", 1, snap.lastPickupItemId)

                client.sendDoorUpdate(0, 1.0, 5)
                t = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < t && snap.doors["0"] == null) Thread.sleep(50)
                assertEquals(
                    "near door push must be echoed with serverVersion+1",
                    6, (snap.doors["0"]?.get("version") as? Number)?.toInt()
                )

                client.sendDoorUpdate(0, -1.0, 6)
                t = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < t) {
                    if (((snap.doors["0"]?.get("version") as? Number)?.toInt() ?: 0) >= 7) break
                    Thread.sleep(50)
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
}
