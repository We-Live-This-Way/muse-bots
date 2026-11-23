package com.welivethisway.bots

import android.app.Activity
import android.content.Intent
import android.view.MenuItem
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.ApiException
import com.welivethisway.bots.api.HubClient
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.SecureStore
import com.welivethisway.bots.api.ServerKind
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Server addresses (user-entered) and sign-in state for both servers. */
class SettingsActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var etRakazoBase: EditText
    private lateinit var etMausbotBase: EditText
    private lateinit var tvRakazoStatus: TextView
    private lateinit var tvMausbotStatus: TextView
    private lateinit var tvSaved: TextView
    private lateinit var etHubBase: EditText
    private lateinit var etHubKey: EditText
    private lateinit var tvHubStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Settings"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        etRakazoBase = findViewById(R.id.etRakazoBase)
        etMausbotBase = findViewById(R.id.etMausbotBase)
        tvRakazoStatus = findViewById(R.id.tvRakazoStatus)
        tvMausbotStatus = findViewById(R.id.tvMausbotStatus)
        tvSaved = findViewById(R.id.tvSaved)
        etHubBase = findViewById(R.id.etHubBase)
        etHubKey = findViewById(R.id.etHubKey)
        tvHubStatus = findViewById(R.id.tvHubStatus)

        etRakazoBase.setText(Prefs.baseUrl(this, ServerKind.RAKAZO) ?: "")
        etMausbotBase.setText(Prefs.baseUrl(this, ServerKind.MAUSBOT) ?: "")
        etHubBase.setText(Prefs.hubBaseUrl(this) ?: "")

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnRakazoSignIn).setOnClickListener {
            if (save()) startActivity(Intent(this, RakazoSignInActivity::class.java))
        }
        findViewById<Button>(R.id.btnRakazoSignOut).setOnClickListener {
            SecureStore.remove(this, SecureStore.KEY_RAKAZO_TOKEN)
            SecureStore.remove(this, SecureStore.KEY_RAKAZO_SPACE)
            refreshStatus()
            Toast.makeText(this, "Signed out of Rakazo.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnMausbotPair).setOnClickListener {
            if (save()) startActivity(Intent(this, MausBotPairActivity::class.java))
        }
        findViewById<Button>(R.id.btnMausbotSignOut).setOnClickListener {
            SecureStore.remove(this, SecureStore.KEY_MAUSBOT_TOKEN)
            refreshStatus()
            Toast.makeText(this, "Unpaired MausBot.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnHubVerify).setOnClickListener { verifyHub() }
        findViewById<Button>(R.id.btnHubOpen).setOnClickListener {
            if (saveHubKey()) startActivity(Intent(this, HubActivity::class.java))
        }
        findViewById<Button>(R.id.btnHubSignOut).setOnClickListener {
            SecureStore.remove(this, SecureStore.KEY_HUB_KEY)
            etHubKey.setText("")
            refreshStatus()
            Toast.makeText(this, "Signed out of the hub.", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdown()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Saves both addresses. Blank clears that server. Returns false when a typed address is unusable. */
    private fun save(): Boolean {
        val rawR = etRakazoBase.text.toString()
        val rawM = etMausbotBase.text.toString()
        if (rawR.isNotBlank() && Prefs.cleanBaseUrl(rawR) == null) {
            tvSaved.text = "Rakazo address must start with http:// or https://"
            return false
        }
        if (rawM.isNotBlank() && Prefs.cleanBaseUrl(rawM) == null) {
            tvSaved.text = "MausBot address must start with http:// or https://"
            return false
        }
        val r = Prefs.cleanBaseUrl(rawR)
        val m = Prefs.cleanBaseUrl(rawM)
        if (r != null) Prefs.setBaseUrl(this, ServerKind.RAKAZO, r) else Prefs.clearBaseUrl(this, ServerKind.RAKAZO)
        if (m != null) Prefs.setBaseUrl(this, ServerKind.MAUSBOT, m) else Prefs.clearBaseUrl(this, ServerKind.MAUSBOT)
        if (!saveHubBase()) return false
        saveHubKey()
        tvSaved.text = "Addresses saved."
        return true
    }

    /** Saves the hub address. Blank clears it. False when a typed address is unusable. */
    private fun saveHubBase(): Boolean {
        val raw = etHubBase.text.toString()
        if (raw.isNotBlank() && Prefs.cleanBaseUrl(raw) == null) {
            tvSaved.text = "Hub address must start with http:// or https://"
            return false
        }
        val h = Prefs.cleanBaseUrl(raw)
        if (h != null) Prefs.setHubBaseUrl(this, h) else Prefs.clearHubBaseUrl(this)
        return true
    }

    /** Stores a typed hub key (encrypted). True when a key is stored or the field is blank. */
    private fun saveHubKey(): Boolean {
        val typed = etHubKey.text.toString().trim()
        if (typed.isEmpty()) return true
        if (!SecureStore.put(this, SecureStore.KEY_HUB_KEY, typed)) {
            tvSaved.text = "Could not store the hub key — try again."
            return false
        }
        return true
    }

    /** Checks the hub key against GET /api/me. */
    private fun verifyHub() {
        if (!saveHubBase() || !saveHubKey()) return
        val base = Prefs.hubBaseUrl(this)
        val key = SecureStore.get(this, SecureStore.KEY_HUB_KEY)
        if (base == null || key == null) {
            tvHubStatus.text = "Enter the hub address and your member key first."
            return
        }
        tvHubStatus.text = "Checking…"
        bg.execute {
            try {
                val me = HubClient(base).me(key)
                runOnUiThread {
                    tvHubStatus.text = "Signed in as ${me.name}${if (me.isAdmin) " (hub owner)" else ""}."
                }
            } catch (e: ApiException) {
                if (e.code == 401) SecureStore.remove(this, SecureStore.KEY_HUB_KEY)
                runOnUiThread {
                    tvHubStatus.text = e.message ?: "Check failed."
                    Toast.makeText(this, e.message ?: "Check failed.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvHubStatus.text = "Unreachable (${e.message})"
                }
            }
        }
    }

    private fun refreshStatus() {
        val rTok = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN)
        tvRakazoStatus.text = if (rTok != null) "Signed in (token saved on this phone)." else "Not signed in."
        val mTok = SecureStore.get(this, SecureStore.KEY_MAUSBOT_TOKEN)
        tvMausbotStatus.text = if (mTok != null) "Paired (token saved on this phone)." else "Not paired."
        val hTok = SecureStore.get(this, SecureStore.KEY_HUB_KEY)
        tvHubStatus.text = if (hTok != null) "Key saved on this phone — tap Verify to check it." else "No key saved."
    }
}
