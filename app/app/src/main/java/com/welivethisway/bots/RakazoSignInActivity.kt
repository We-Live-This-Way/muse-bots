package com.welivethisway.bots

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.RakazoClient
import com.welivethisway.bots.api.SecureStore
import com.welivethisway.bots.api.ServerKind
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Rakazo email sign-in. The password is used once and never stored. */
class RakazoSignInActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rakazo_signin)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btn = findViewById<Button>(R.id.btnSignIn)
        btn.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            if (email.isEmpty() || password.isEmpty()) {
                tvStatus.text = "Enter your email and password."
                return@setOnClickListener
            }
            val base = Prefs.baseUrl(this, ServerKind.RAKAZO)
            if (base == null) {
                tvStatus.text = "Add the Rakazo address in Settings first."
                return@setOnClickListener
            }
            btn.isEnabled = false
            tvStatus.text = "Signing in…"
            bg.execute {
                try {
                    val client = RakazoClient(base)
                    val token = client.signIn(email, password)
                    SecureStore.put(this, SecureStore.KEY_RAKAZO_TOKEN, token)
                    // Learn the space id now so chat calls work.
                    try {
                        val (spaceId, _) = client.botsFromSpaces(client.spacesList(token))
                        if (spaceId != null) SecureStore.put(this, SecureStore.KEY_RAKAZO_SPACE, spaceId)
                    } catch (e: Exception) {
                        // The space id is re-learned on the next refresh.
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Signed in.", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true
                        tvStatus.text = e.message ?: "Sign-in failed."
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
