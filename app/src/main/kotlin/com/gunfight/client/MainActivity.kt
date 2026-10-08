package com.gunfight.client

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.gunfight.protocol.GameClient
import com.gunfight.protocol.Net
import kotlin.concurrent.thread

/** Phase 1 main screen: server IP + port + player name, tap Connect to spectate. */
class MainActivity : Activity() {

    private var client: GameClient? = null
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var form: LinearLayout
    private var view: GameView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        val ip = EditText(this).apply { hint = "Server IP"; setText("127.0.0.1") }
        val port = EditText(this).apply {
            hint = "Port"
            setText(Net.DEFAULT_PORT.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val name = EditText(this).apply { hint = "Player name"; setText("Android") }
        statusText = TextView(this).apply { text = "not connected" }
        connectBtn = Button(this).apply { text = "开始游戏" }
        for (v in listOf(ip, port, name, connectBtn, statusText)) {
            form.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(form)

        connectBtn.setOnClickListener {
            connectBtn.isEnabled = false
            statusText.text = "connecting..."
            val host = ip.text.toString().ifBlank { "127.0.0.1" }
            val p = port.text.toString().toIntOrNull() ?: Net.DEFAULT_PORT
            thread(name = "gunfight-connect", isDaemon = true) {
                val c = GameClient(host, p, name.text.toString().ifBlank { "Android" })
                val ok = c.connect { err -> runOnUiThread { statusText.text = err } }
                runOnUiThread {
                    if (ok) {
                        client = c
                        showSpectate(c)
                    } else {
                        connectBtn.isEnabled = true
                        try {
                            c.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    private fun showSpectate(c: GameClient) {
        val v = GameView(this, GameSettings.get(this))
        v.client = c
        view = v
        setContentView(v)
    }

    override fun onBackPressed() {
        if (view != null) {
            try {
                client?.close()
            } catch (_: Exception) {
            }
            client = null
            view = null
            setContentView(form)
            connectBtn.isEnabled = true
            statusText.text = "disconnected"
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
