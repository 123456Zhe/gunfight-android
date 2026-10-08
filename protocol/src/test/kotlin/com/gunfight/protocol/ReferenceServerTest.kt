package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Interop against the REAL Python reference server (reference/network.py):
 * starts refserver/helper_server.py, performs a true UDP handshake, and
 * asserts the initial state broadcast (init_players) lands in the snapshot.
 */
class ReferenceServerTest {
    @Test(timeout = 60000) fun handshakeWithRealPythonServer() {
        val helperUrl = javaClass.classLoader.getResource("refserver/helper_server.py")
            ?: throw IllegalStateException("refserver/helper_server.py not on test classpath")
        val helper = File(helperUrl.toURI())
        val proc = ProcessBuilder("python3", helper.absolutePath)
            .directory(helper.parentFile)
            .redirectErrorStream(true)
            .start()
        try {
            val readyDeadline = System.currentTimeMillis() + 20000
            val out = proc.inputStream.bufferedReader()
            var ready = false
            while (System.currentTimeMillis() < readyDeadline) {
                if (out.ready()) {
                    if (out.readLine()?.contains("READY") == true) { ready = true; break }
                } else Thread.sleep(50)
            }
            assertTrue("reference python server did not print READY", ready)

            val client = GameClient("127.0.0.1", Net.DEFAULT_PORT, "KotlinInteg")
            try {
                assertTrue("handshake with reference server failed", client.connect())
                assertTrue("client_id not assigned", client.handshake.clientId > 0)
                val deadline = System.currentTimeMillis() + 10000
                while (System.currentTimeMillis() < deadline && client.snapshot.players.isEmpty()) {
                    Thread.sleep(50)
                }
                assertTrue(
                    "no init_players state received from reference server",
                    client.snapshot.players.isNotEmpty()
                )
                assertFalse(client.isTimedOut())
            } finally {
                client.close()
            }
        } finally {
            proc.destroy()
            proc.waitFor(5, TimeUnit.SECONDS)
            proc.destroyForcibly()
        }
    }
}
