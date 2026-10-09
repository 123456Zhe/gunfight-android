package com.gunfight.client

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gunfight.protocol.GameClient
import com.gunfight.protocol.Net
import kotlin.concurrent.thread

/** Connection screen: server IP + port + player name, then the playable view. */
class MainActivity : Activity() {

    private var client: GameClient? = null
    private var view: GameView? = null
    private var connecting = false

    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var form: LinearLayout
    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private lateinit var nameField: EditText
    private lateinit var chatRow: LinearLayout
    private lateinit var chatInput: EditText

    private val prefs by lazy { getSharedPreferences("gunfight", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = GameSettings.get(this)
        form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        ipField = EditText(this).apply {
            hint = "Server IP"
            setText(prefs.getString("host", "127.0.0.1"))
        }
        portField = EditText(this).apply {
            hint = "Port"
            setText(prefs.getInt("port", settings.serverPort).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        nameField = EditText(this).apply {
            hint = "Player name"
            setText(prefs.getString("name", "Android"))
        }
        statusText = TextView(this).apply { text = "未连接" }
        connectBtn = Button(this).apply { text = "开始游戏" }
        connectBtn.setOnClickListener { startConnect() }
        for (v in listOf(ipField, portField, nameField, connectBtn, statusText)) {
            form.addView(
                v, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(form)
    }

    private fun startConnect() {
        if (connecting) return
        connecting = true
        connectBtn.isEnabled = false
        statusText.text = "连接中…"
        val host = ipField.text.toString().ifBlank { "127.0.0.1" }
        val port = portField.text.toString().toIntOrNull() ?: Net.DEFAULT_PORT
        val name = nameField.text.toString().ifBlank { "Android" }
        prefs.edit().putString("host", host).putInt("port", port).putString("name", name).apply()

        thread(name = "gunfight-connect", isDaemon = true) {
            val c = GameClient(host, port, name, object : GameClient.Listener() {
                override fun onTimeout() {
                    runOnUiThread { backToForm("连接超时，已断开") }
                }

                override fun onKicked(reason: String) {
                    runOnUiThread { backToForm("被服务器踢出: " + reason) }
                }
            })
            val ok = c.connect { err -> runOnUiThread { statusText.text = err } }
            runOnUiThread {
                connecting = false
                if (ok) {
                    client = c
                    showGame(c)
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

    private fun showGame(c: GameClient) {
        val settings = GameSettings.get(this)
        val v = GameView(this, settings)
        v.client = c
        v.onChatToggle = { toggleChat() }
        view = v

        chatInput = EditText(this).apply {
            hint = "聊天 / 命令（.help）"
            setSingleLine()
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.LTGRAY)
            setBackgroundColor(android.graphics.Color.argb(190, 0, 0, 0))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendChat()
                    true
                } else {
                    false
                }
            }
        }
        val sendBtn = Button(this).apply {
            text = "发送"
            setOnClickListener { sendChat() }
        }
        chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                sendBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val root = FrameLayout(this)
        root.addView(
            v,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            chatRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )
        statusText.text = "已连接"
        setContentView(root)
    }

    private fun toggleChat() {
        if (chatRow.visibility == View.VISIBLE) {
            hideChat()
        } else {
            chatRow.visibility = View.VISIBLE
            chatInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideChat() {
        if (!::chatRow.isInitialized || chatRow.visibility != View.VISIBLE) return
        chatRow.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(chatInput.windowToken, 0)
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        chatInput.setText("")
        hideChat()
        if (text.isEmpty()) return
        client?.sendChat(text.take(GameSettings.get(this).chatMaxLength))
    }

    private fun backToForm(message: String) {
        if (isFinishing) return
        hideChat()
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        view = null
        connecting = false
        connectBtn.isEnabled = true
        statusText.text = message
        setContentView(form)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (view != null) backToForm("已断开") else super.onBackPressed()
    }

    override fun onDestroy() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
