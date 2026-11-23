package com.welivethisway.bots

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.MausBotClient
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.SecureStore
import com.welivethisway.bots.api.ServerKind
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** MausBot pairing: a 12-char code or an omb_pair_ QR credential. */
class MausBotPairActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mausbot_pair)
        val etCode = findViewById<EditText>(R.id.etCode)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btn = findViewById<Button>(R.id.btnPair)
        btn.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.isEmpty()) {
                tvStatus.text = "Paste the pairing code first."
                return@setOnClickListener
            }
            val base = Prefs.baseUrl(this, ServerKind.MAUSBOT)
            if (base == null) {
                tvStatus.text = "Add the MausBot address in Settings first."
                return@setOnClickListener
            }
            btn.isEnabled = false
            tvStatus.text = "Pairing…"
            bg.execute {
                try {
                    val token = MausBotClient(base).pair(code)
                    SecureStore.put(this, SecureStore.KEY_MAUSBOT_TOKEN, token)
                    runOnUiThread {
                        Toast.makeText(this, "Paired.", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true
                        tvStatus.text = e.message ?: "Pairing failed."
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdown()
    }
}
