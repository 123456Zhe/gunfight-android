package com.gunfight.protocol

import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Interop against the REAL Python reference server (zd-2d-gunfight/network.py):
 * starts refserver/helper_server.py, performs a true UDP handshake, and
 * asserts the initial state broadcast (init_players) lands in the snapshot.
 *
 * The reference implementation lives in another repository, so the test skips
 * itself unless reference/network.py exists or GUNFIGHT_REFERENCE_NETWORK is set.
 */
class ReferenceServerTest {
    private fun helperScript(): File? {
        val url = javaClass.classLoader.getResource("refserver/helper_server.py") ?: return null
        return File(url.toURI())
    }

    private fun referenceNetworkPy(): File? {
        System.getenv("GUNFIGHT_REFERENCE_NETWORK")?.let {
            val f = File(it)
            if (f.isFile) return f
        }
        val helper = helperScript() ?: return null
        val repoRoot = helper.parentFile.parentFile.parentFile.parentFile.parentFile
        val f = File(repoRoot, "reference/network.py")
        return if (f.isFile) f else null
    }

    @Test(timeout = 60000) fun handshakeWithRealPythonServer() {
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
            .apply { environment()["GUNFIGHT_REFERENCE_NETWORK"] = networkPy!!.absolutePath }
            .start()
        try {
            val readyDeadline = System.currentTimeMillis() + 20000
            val out = proc.inputStream.bufferedReader()
            var port = -1
            while (System.currentTimeMillis() < readyDeadline) {
                if (out.ready()) {
                    val line = out.readLine() ?: break
                    if (line.contains("READY")) {
                        port = Regex("port=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                        if (port > 0) break
                    }
                    if (line.contains("SKIP") || line.contains("ERROR")) break
                } else Thread.sleep(50)
            }
            assertTrue("reference python server did not print READY port=<n>", port > 0)

            val client = GameClient("127.0.0.1", port, "KotlinInteg")
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

    private fun pythonAvailable(): Boolean = try {
        val p = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        p.waitFor(5, TimeUnit.SECONDS)
        p.exitValue() == 0
    } catch (_: IOException) {
        false
    } catch (_: InterruptedException) {
        false
    }
}
