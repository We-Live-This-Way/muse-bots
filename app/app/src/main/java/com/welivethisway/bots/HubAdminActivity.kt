package com.welivethisway.bots

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.ApiException
import com.welivethisway.bots.api.HubClient
import com.welivethisway.bots.api.HubGroup
import com.welivethisway.bots.api.HubJoinRequest
import com.welivethisway.bots.api.HubMember
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.SecureStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hub admin tools (the operator is the hub owner). Every section checks for
 * admin first: a non-admin key gets a plain message instead of the tools,
 * and a 403 from any admin call is handled the same way.
 * Keys the hub shows once (new member, rotated key) are displayed with a
 * copy button and a warning — the app never stores them anywhere else.
 */
class HubAdminActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var tvStatus: TextView
    private lateinit var llRequests: LinearLayout
    private lateinit var tvNoRequests: TextView
    private lateinit var llMembers: LinearLayout
    private lateinit var tvNoMembers: TextView
    private lateinit var etInviteName: EditText
    private lateinit var etInviteDays: EditText
    private lateinit var etBotName: EditText
    private lateinit var etGroupName: EditText
    private lateinit var llGroups: LinearLayout
    private lateinit var tvNoGroups: TextView
    private lateinit var tvNewKeyLabel: TextView
    private lateinit var tvNewKey: TextView
    private lateinit var btnCopyKey: Button

    private var isAdmin: Boolean? = null
    private var myName: String? = null
    private var lastShownKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub_admin)
        title = "Hub admin"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        tvStatus = findViewById(R.id.tvAdminStatus)
        llRequests = findViewById(R.id.llRequests)
        tvNoRequests = findViewById(R.id.tvNoRequests)
        llMembers = findViewById(R.id.llMembers)
        tvNoMembers = findViewById(R.id.tvNoMembers)
        etInviteName = findViewById(R.id.etInviteName)
        etInviteDays = findViewById(R.id.etInviteDays)
        etBotName = findViewById(R.id.etBotName)
        etGroupName = findViewById(R.id.etGroupName)
        llGroups = findViewById(R.id.llGroups)
        tvNoGroups = findViewById(R.id.tvNoGroups)
        tvNewKeyLabel = findViewById(R.id.tvNewKeyLabel)
        tvNewKey = findViewById(R.id.tvNewKey)
        btnCopyKey = findViewById(R.id.btnCopyKey)

        findViewById<Button>(R.id.btnInvite).setOnClickListener { createInvite() }
        findViewById<Button>(R.id.btnAddMember).setOnClickListener { addBot("member") }
        findViewById<Button>(R.id.btnAddVisitor).setOnClickListener { addBot("guest") }
        findViewById<Button>(R.id.btnLoadMembers).setOnClickListener { loadMembers() }
        findViewById<Button>(R.id.btnCreateGroup).setOnClickListener { createGroup() }
        findViewById<Button>(R.id.btnLoadGroups).setOnClickListener { loadGroups() }
        findViewById<Button>(R.id.btnListening).setOnClickListener {
            if (!needAdmin()) return@setOnClickListener
            startActivity(Intent(this, HubListeningActivity::class.java))
        }
        btnCopyKey.setOnClickListener { copyShownKey() }
        checkAdmin()
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

    private fun hub(): Pair<String, String>? {
        val base = Prefs.hubBaseUrl(this) ?: return null
        val key = SecureStore.get(this, SecureStore.KEY_HUB_KEY) ?: return null
        return base to key
    }

    /** 403 from an admin call means this key is not the owner's. */
    private fun handle403(e: ApiException): Boolean {
        if (e.code == 403) {
            isAdmin = false
            runOnUiThread {
                tvStatus.text = "Admin tools need the hub owner's key — this key is signed in as ${myName ?: "a member"}."
                Toast.makeText(this, "That needs the hub owner's key.", Toast.LENGTH_LONG).show()
            }
            return true
        }
        return false
    }

    private fun checkAdmin() {
        val pair = hub()
        if (pair == null) {
            tvStatus.text = "Not set up — add the hub address and your member key in Settings."
            return
        }
        val (base, key) = pair
        tvStatus.text = "Checking…"
        bg.execute {
            try {
                val me = HubClient(base).me(key)
                myName = me.name
                isAdmin = me.isAdmin
                runOnUiThread {
                    tvStatus.text = if (me.isAdmin) {
                        "Signed in as ${me.name} (hub owner)."
                    } else {
                        "Admin tools need the hub owner's key — this key is signed in as ${me.name}."
                    }
                }
                if (me.isAdmin) loadRequests()
                if (me.isAdmin) loadGroups()
            } catch (e: ApiException) {
                if (e.code == 401) SecureStore.remove(this, SecureStore.KEY_HUB_KEY)
                runOnUiThread {
                    tvStatus.text = e.message ?: "Could not check."
                    Toast.makeText(this, e.message ?: "Could not check.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Unreachable (${e.message})" }
            }
        }
    }

    private fun needAdmin(): Boolean {
        if (isAdmin == true) return true
        Toast.makeText(
            this,
            "Admin tools need the hub owner's key — this key is signed in as ${myName ?: "a member"}.",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    // ---- invite ----

    private fun createInvite() {
        if (!needAdmin()) return
        val pair = hub() ?: return
        val name = etInviteName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter their name first.", Toast.LENGTH_SHORT).show()
            return
        }
        val days = etInviteDays.text.toString().toIntOrNull() ?: 30
        val (base, key) = pair
        bg.execute {
            try {
                val result = HubClient(base).invite(key, name, days)
                runOnUiThread { showInvite(result.text) }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Invite failed.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Invite failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** The invite text is ready to share as-is (link + key inside). */
    private fun showInvite(text: String) {
        if (text.isEmpty()) {
            Toast.makeText(this, "Invite created, but no invite text came back.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Invite ready — share it")
            .setMessage(text)
            .setPositiveButton("Share") { _, _ -> shareText(text) }
            .setNeutralButton("Copy") { _, _ -> copyText(text, "Invite copied.") }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share invite"))
    }

    private fun copyText(text: String, doneMsg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Bot Hub", text))
        Toast.makeText(this, doneMsg, Toast.LENGTH_SHORT).show()
    }

    // ---- join requests ----

    private fun loadRequests() {
        if (!needAdmin()) return
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val list = HubClient(base).requests(key)
                runOnUiThread { showRequests(list) }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not load requests.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not load requests.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showRequests(list: List<HubJoinRequest>) {
        llRequests.removeAllViews()
        val pending = list.filter { it.status == "pending" }
        tvNoRequests.visibility = if (pending.isEmpty()) View.VISIBLE else View.GONE
        for (req in pending) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_bot, llRequests, false)
            val tvName = row.findViewById<TextView>(R.id.tvName)
            val tvSub = row.findViewById<TextView>(R.id.tvSub)
            tvName.text = req.name
            val sub = listOfNotNull(
                req.platform,
                req.note,
                req.createdAtMs?.let { "asked ${DATE_FMT.format(Date(it))}" }
            ).joinToString(" — ")
            tvSub.text = sub
            tvSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            row.findViewById<TextView>(R.id.tvAvatar).text = req.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            row.setOnClickListener { showRequestDialog(req) }
            llRequests.addView(row)
        }
    }

    private fun showRequestDialog(req: HubJoinRequest) {
        val detail = listOfNotNull(
            "Name: ${req.name}",
            req.platform?.let { "Platform: $it" },
            req.note?.let { "Note: $it" },
            req.contact?.let { "Contact: $it" }
        ).joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("Join request")
            .setMessage(detail)
            .setPositiveButton("Approve as visitor") { _, _ -> decideRequest(req, "guest") }
            .setNeutralButton("Approve as member") { _, _ -> decideRequest(req, "member") }
            .setNegativeButton("Deny") { _, _ -> decideRequest(req, null) }
            .show()
    }

    private fun decideRequest(req: HubJoinRequest, asRole: String?) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val client = HubClient(base)
                if (asRole == null) {
                    client.denyRequest(key, req.id)
                    runOnUiThread {
                        Toast.makeText(this, "Request denied.", Toast.LENGTH_SHORT).show()
                        loadRequests()
                    }
                } else {
                    client.approveRequest(key, req.id, asRole)
                    runOnUiThread {
                        // The key is minted only when they claim it — the operator has nothing to send them.
                        Toast.makeText(
                            this,
                            "Approved as $asRole — they'll pick up their key with their claim code.",
                            Toast.LENGTH_LONG
                        ).show()
                        loadRequests()
                    }
                }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "That didn't work.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "That didn't work.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---- add bot ----

    private fun addBot(role: String) {
        if (!needAdmin()) return
        val pair = hub() ?: return
        val name = etBotName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter the bot's name first.", Toast.LENGTH_SHORT).show()
            return
        }
        val (base, key) = pair
        bg.execute {
            try {
                val (memberName, newKey) = HubClient(base).addMember(key, name, "bot", role)
                runOnUiThread { showNewKey("$memberName's hub key", newKey) }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not add that bot.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not add that bot.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** A key the hub shows once. Displayed with a copy button and a warning; never stored. */
    private fun showNewKey(label: String, newKey: String?) {
        lastShownKey = newKey
        if (newKey.isNullOrEmpty()) {
            tvNewKeyLabel.visibility = View.GONE
            tvNewKey.visibility = View.GONE
            btnCopyKey.visibility = View.GONE
            Toast.makeText(this, "Added, but no key came back — check the hub.", Toast.LENGTH_LONG).show()
            return
        }
        tvNewKeyLabel.text = "$label — copy it now, it is shown once and won't be shown again."
        tvNewKeyLabel.visibility = View.VISIBLE
        tvNewKey.text = newKey
        tvNewKey.visibility = View.VISIBLE
        btnCopyKey.visibility = View.VISIBLE
    }

    private fun copyShownKey() {
        val k = lastShownKey ?: return
        copyText(k, "Key copied.")
    }

    // ---- rotate key ----

    private fun loadMembers() {
        if (!needAdmin()) return
        val pair = hub() ?: return
        val (base, key) = pair
        tvNoMembers.text = "Loading…"
        bg.execute {
            try {
                val list = HubClient(base).adminMembers(key)
                runOnUiThread { showMembers(list) }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    tvNoMembers.text = e.message ?: "Could not load members."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvNoMembers.text = e.message ?: "Could not load members."
                }
            }
        }
    }

    private fun showMembers(list: List<HubMember>) {
        llMembers.removeAllViews()
        tvNoMembers.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) tvNoMembers.text = "No members found."
        for (m in list) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_bot, llMembers, false)
            val tvName = row.findViewById<TextView>(R.id.tvName)
            val tvSub = row.findViewById<TextView>(R.id.tvSub)
            tvName.text = m.name
            val sub = listOfNotNull(
                if (m.admin) "hub owner" else m.role,
                m.kind
            ).joinToString(" — ")
            tvSub.text = sub
            row.findViewById<TextView>(R.id.tvAvatar).text = m.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            row.setOnClickListener { confirmRotate(m) }
            llMembers.addView(row)
        }
    }

    private fun confirmRotate(m: HubMember) {
        AlertDialog.Builder(this)
            .setTitle("Rotate ${m.name}'s key?")
            .setMessage("They get a fresh key. Their old key stops working right away.")
            .setPositiveButton("Rotate") { _, _ -> rotateKey(m) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rotateKey(m: HubMember) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val (_, newKey) = HubClient(base).rotateKey(key, m.id)
                runOnUiThread { showNewKey("${m.name}'s new key", newKey) }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Rotate failed.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Rotate failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---- groups ----

    /** @groups for broadcasts: @sales, @coders. @everyone is built in — it is not a group. */
    private fun createGroup() {
        if (!needAdmin()) return
        val pair = hub() ?: return
        val name = etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter the group's name first.", Toast.LENGTH_SHORT).show()
            return
        }
        val (base, key) = pair
        bg.execute {
            try {
                val g = HubClient(base).createGroup(key, name)
                runOnUiThread {
                    Toast.makeText(this, "Group @${g?.name ?: name} created.", Toast.LENGTH_SHORT).show()
                    etGroupName.text.clear()
                    loadGroups()
                }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not create that group.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not create that group.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadGroups() {
        if (!needAdmin()) return
        val pair = hub() ?: return
        val (base, key) = pair
        tvNoGroups.text = "Loading…"
        bg.execute {
            try {
                val list = HubClient(base).adminGroups(key)
                runOnUiThread { showGroups(list) }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    tvNoGroups.text = e.message ?: "Could not load groups."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvNoGroups.text = e.message ?: "Could not load groups."
                }
            }
        }
    }

    private fun showGroups(list: List<HubGroup>) {
        llGroups.removeAllViews()
        tvNoGroups.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) tvNoGroups.text = "No groups yet."
        for (g in list) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_bot, llGroups, false)
            val tvName = row.findViewById<TextView>(R.id.tvName)
            val tvSub = row.findViewById<TextView>(R.id.tvSub)
            tvName.text = "@${g.name}"
            val sub = if (g.members.isEmpty()) "no members yet" else g.members.joinToString(", ")
            tvSub.text = sub
            row.findViewById<TextView>(R.id.tvAvatar).text = "@"
            row.setOnClickListener { showGroupDialog(g) }
            llGroups.addView(row)
        }
    }

    private fun showGroupDialog(g: HubGroup) {
        val detail = if (g.members.isEmpty()) {
            "@${g.name} has no members yet."
        } else {
            "@${g.name}:\n" + g.members.joinToString("\n") { "• $it" }
        }
        AlertDialog.Builder(this)
            .setTitle("@${g.name}")
            .setMessage(detail)
            .setPositiveButton("Add member") { _, _ -> pickMemberToAdd(g) }
            .setNeutralButton("Remove member") { _, _ -> pickMemberToRemove(g) }
            .setNegativeButton("Delete group") { _, _ -> confirmDeleteGroup(g) }
            .show()
    }

    private fun pickMemberToAdd(g: HubGroup) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val names = HubClient(base).memberNames(key)
                    .filter { it !in g.members }
                runOnUiThread {
                    if (names.isEmpty()) {
                        Toast.makeText(this, "Everyone is already in @${g.name}.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Add to @${g.name}")
                        .setItems(names.toTypedArray()) { _, which ->
                            setGroupMember(g, names[which], true)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not load members.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pickMemberToRemove(g: HubGroup) {
        if (g.members.isEmpty()) {
            Toast.makeText(this, "@${g.name} has no members to remove.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove from @${g.name}")
            .setItems(g.members.toTypedArray()) { _, which ->
                setGroupMember(g, g.members[which], false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setGroupMember(g: HubGroup, member: String, on: Boolean) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                HubClient(base).setGroupMember(key, g.name, member, on)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (on) "$member added to @${g.name}." else "$member removed from @${g.name}.",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadGroups()
                }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "That didn't work.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "That didn't work.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDeleteGroup(g: HubGroup) {
        AlertDialog.Builder(this)
            .setTitle("Delete @${g.name}?")
            .setMessage("The group goes away. @mentions of it stop working.")
            .setPositiveButton("Delete") { _, _ -> deleteGroup(g) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGroup(g: HubGroup) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                HubClient(base).deleteGroup(key, g.name)
                runOnUiThread {
                    Toast.makeText(this, "@${g.name} deleted.", Toast.LENGTH_SHORT).show()
                    loadGroups()
                }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not delete that group.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not delete that group.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private val DATE_FMT = SimpleDateFormat("MMM d", Locale.getDefault())
    }
}
