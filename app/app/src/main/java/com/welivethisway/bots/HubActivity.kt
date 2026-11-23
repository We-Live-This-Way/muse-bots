package com.welivethisway.bots

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.ApiException
import com.welivethisway.bots.api.BotItem
import com.welivethisway.bots.api.HubClient
import com.welivethisway.bots.api.HubGroup
import com.welivethisway.bots.api.HubRoomItem
import com.welivethisway.bots.api.MausBotClient
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.RakazoClient
import com.welivethisway.bots.api.SecureStore
import com.welivethisway.bots.api.ServerKind
import com.welivethisway.bots.api.serverNameFromUrl
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bot Hub: rooms and DMs in one list. Poll-based — re-reads on open,
 * on resume and on Refresh (the hub has no live stream).
 */
class HubActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var listHub: ListView
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnAdmin: Button

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Room(val item: HubRoomItem) : Row()
        data class PrivateToggle(val count: Int, val expanded: Boolean) : Row()
        /** Hidden DMs live here until they get a new message (or are unhidden). */
        data class HiddenDms(val count: Int) : Row()
    }

    private val rows = ArrayList<Row>()
    private lateinit var adapter: HubAdapter
    private var isAdmin = false
    private var pendingRequests = -1
    private var privateExpanded = false
    private var lastOverview: HubClient.Overview? = null
    private var canGroupWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        title = "Bot Hub"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        listHub = findViewById(R.id.listHub)
        tvStatus = findViewById(R.id.tvHubStatus)
        tvEmpty = findViewById(R.id.tvHubEmpty)
        btnAdmin = findViewById(R.id.btnHubAdmin)
        adapter = HubAdapter()
        listHub.adapter = adapter
        listHub.setOnItemClickListener { _, _, pos, _ ->
            val row = rows[pos]
            if (row is Row.Room) openRoom(row.item)
            if (row is Row.PrivateToggle) {
                privateExpanded = !privateExpanded
                buildRows()
            }
            if (row is Row.HiddenDms) showHiddenDms()
        }
        // Long-press a DM to hide it from this phone's list.
        listHub.setOnItemLongClickListener { _, _, pos, _ ->
            val row = rows[pos]
            if (row is Row.Room && row.item.isDm) {
                hideDmDialog(row.item)
                true
            } else false
        }
        findViewById<Button>(R.id.btnHubRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnHubNewDm).setOnClickListener { newDm() }
        findViewById<Button>(R.id.btnHubBroadcast).setOnClickListener { broadcast() }
        btnAdmin.setOnClickListener {
            startActivity(Intent(this, HubAdminActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    private fun openRoom(item: HubRoomItem) {
        startActivity(
            Intent(this, HubRoomActivity::class.java).apply {
                putExtra("room", item.room)
                putExtra("title", item.title)
                putExtra("isDm", item.isDm)
            }
        )
    }

    private fun refresh() {
        val pair = hub()
        if (pair == null) {
            tvStatus.text = "Not set up — add the hub address and your member key in Settings."
            rows.clear()
            adapter.notifyDataSetChanged()
            tvEmpty.visibility = View.VISIBLE
            listHub.visibility = View.GONE
            return
        }
        val (base, key) = pair
        tvStatus.text = "Loading…"
        bg.execute {
            try {
                val client = HubClient(base)
                val me = try {
                    client.me(key)
                } catch (e: Exception) {
                    null
                }
                val overview = client.overview(key)
                isAdmin = me?.isAdmin == true
                pendingRequests = overview.pendingRequests
                lastOverview = overview
                canGroupWake = me?.canGroupWake == true
                runOnUiThread {
                    buildRows()
                    val lines = ArrayList<String>()
                    val total = overview.rooms.size + overview.dms.size
                    if (total > 0) {
                        lines += "$total conversation${if (total == 1) "" else "s"}"
                    } else {
                        lines += "No rooms yet."
                    }
                    if (me != null) {
                        lines += "Signed in as ${me.name}${if (me.isAdmin) " (hub owner)" else ""}."
                    }
                    if (pendingRequests > 0) {
                        lines += "$pendingRequests join request${if (pendingRequests == 1) "" else "s"} waiting — see Admin."
                    }
                    tvStatus.text = lines.joinToString("\n")
                }
            } catch (e: ApiException) {
                if (e.code == 401) {
                    SecureStore.remove(this, SecureStore.KEY_HUB_KEY)
                }
                runOnUiThread {
                    tvStatus.text = e.message ?: "Could not load the hub."
                    Toast.makeText(this, e.message ?: "Could not load the hub.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Unreachable (${e.message})"
                }
            }
        }
    }

    /** Public rooms + DMs first; private rooms sit collapsed under one tap-to-expand row. */
    private fun buildRows() {
        val overview = lastOverview ?: return
        val newRows = ArrayList<Row>()
        val publicRooms = overview.rooms.filter { !it.isPrivate }
        val privateRooms = overview.rooms.filter { it.isPrivate }
        if (publicRooms.isNotEmpty()) {
            newRows += Row.Header("Rooms")
            publicRooms.forEach { newRows += Row.Room(it) }
        }
        if (privateRooms.isNotEmpty()) {
            newRows += Row.PrivateToggle(privateRooms.size, privateExpanded)
            if (privateExpanded) privateRooms.forEach { newRows += Row.Room(it) }
        }
        // DMs: hidden ones stay out of the list — until they get a newer
        // message, when the hub brings them back (lastId past the hide).
        val hidden = Prefs.hiddenDms(this).toMutableMap()
        val visibleDms = ArrayList<HubRoomItem>()
        var hiddenCount = 0
        var pruned = false
        for (dm in overview.dms) {
            val at = hidden[dm.room]
            if (at != null && dm.lastId <= at) {
                hiddenCount++
            } else {
                if (at != null) {
                    hidden.remove(dm.room)
                    pruned = true
                }
                visibleDms += dm
            }
        }
        if (pruned) Prefs.saveHiddenDms(this, hidden)
        if (visibleDms.isNotEmpty()) {
            newRows += Row.Header("Direct messages")
            visibleDms.forEach { newRows += Row.Room(it) }
        }
        if (hiddenCount > 0) newRows += Row.HiddenDms(hiddenCount)
        rows.clear()
        rows.addAll(newRows)
        adapter.notifyDataSetChanged()
        val empty = rows.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        listHub.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /**
     * Hides a DM from this phone's list. The hub keeps it — a DM can't
     * be deleted, even by the owner — so it comes back if they send
     * another message. Unhide from the Hidden DMs row below the list.
     */
    private fun hideDmDialog(item: HubRoomItem) {
        AlertDialog.Builder(this)
            .setTitle("Hide this DM?")
            .setMessage("${item.title} stays on the hub — it will come back if they send another message. You can bring it back from Hidden DMs below.")
            .setPositiveButton("Hide") { _, _ ->
                Prefs.hideDm(this, item.room, item.lastId)
                buildRows()
                Toast.makeText(this, "DM hidden.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lists the DMs hidden on this phone; tap one to bring it back. */
    private fun showHiddenDms() {
        val overview = lastOverview ?: return
        val hidden = Prefs.hiddenDms(this)
        val items = overview.dms.filter { hidden.containsKey(it.room) }
        if (items.isEmpty()) {
            Toast.makeText(this, "No hidden DMs.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Hidden DMs")
            .setItems(items.map { it.title }.toTypedArray()) { _, which ->
                Prefs.unhideDm(this, items[which].room)
                buildRows()
                Toast.makeText(this, "${items[which].title} is back in the list.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Broadcast: pick a target (@everyone is built in), type once, post "@group <text>" to #general.
     * If the hub can't be reached the group list can't load — and the
     * group-wake check (GET /api/me) can't run either — so @everyone is
     * still offered, as a DM-only broadcast that skips the hub post.
     * Group targets only appear when the hub actually answers.
     */
    private fun broadcast() {
        val pair = hub()
        if (pair == null) {
            // No hub set up at all: @everyone by DM only.
            pickBroadcastTarget(emptyList(), hubAvailable = false)
            return
        }
        val (base, key) = pair
        Toast.makeText(this, "Loading groups…", Toast.LENGTH_SHORT).show()
        bg.execute {
            try {
                val client = HubClient(base)
                val groups = client.groups(key)
                // Re-check the wake permission fresh: the value from
                // refresh() may be stale if the hub was down then.
                try {
                    canGroupWake = client.me(key).canGroupWake
                } catch (e: Exception) {
                    // Groups loaded, so the hub is up; keep the stored value.
                }
                runOnUiThread {
                    if (!canGroupWake) {
                        Toast.makeText(this, "Broadcast needs the hub owner's key (or a group-wake member).", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    pickBroadcastTarget(groups, hubAvailable = true)
                }
            } catch (e: Exception) {
                // Hub down: no groups, no wake check — @everyone by DM only.
                runOnUiThread { pickBroadcastTarget(emptyList(), hubAvailable = false) }
            }
        }
    }

    private fun pickBroadcastTarget(groups: List<HubGroup>, hubAvailable: Boolean) {
        val targets = ArrayList<Pair<String, String>>() // (tag, label)
        targets += "@everyone" to if (hubAvailable) {
            "@everyone — hub post + a DM to every bot on both servers"
        } else {
            "@everyone — DM to every bot on both servers (hub unreachable)"
        }
        if (hubAvailable) {
            groups.forEach { g ->
                targets += "@${g.name}" to "@${g.name} — ${g.members.size} member${if (g.members.size == 1) "" else "s"}"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Broadcast to whom?")
            .setItems(targets.map { it.second }.toTypedArray()) { _, which ->
                composeBroadcast(targets[which].first, hubAvailable)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun composeBroadcast(tag: String, hubAvailable: Boolean) {
        val input = EditText(this).apply { hint = "Message for $tag…" }
        // Say plainly who the hub post reaches. @everyone also DMs every
        // bot on both servers (confirmed on the next screen); a group
        // post only reaches that group's hub members. When the hub is
        // down there is no hub post at all — DMs only.
        val note = TextView(this).apply {
            text = if (tag == "@everyone") {
                if (hubAvailable) {
                    "Posts to #general, and also sends this as a direct message to every bot on both servers. You'll confirm the bot count before sending."
                } else {
                    "The hub can't be reached, so this skips the #general post and sends only as a direct message to every bot on both servers. You'll confirm the bot count before sending."
                }
            } else {
                "Posts to #general — reaches this group's hub members. Bots on both servers wake through their own paths; bots not on the hub won't see it."
            }
            setTextColor(Color.parseColor("#5A6B7C"))
            textSize = 13f
            setPadding(0, 12, 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(input)
            addView(note)
        }
        AlertDialog.Builder(this)
            .setTitle("Broadcast $tag")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Type the message first.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (tag == "@everyone") confirmEveryoneBroadcast(text, hubAvailable) else sendBroadcast(tag, text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * @everyone also DMs every bot on both servers, so confirm first:
     * load both rosters, show the counts, and only then send. A server
     * that isn't signed in or can't be reached contributes no DMs and
     * says so in the dialog instead of failing the whole broadcast.
     * When the hub is down the dialog says so up front — there is no
     * #general post in that case, DMs only.
     */
    private fun confirmEveryoneBroadcast(text: String, hubAvailable: Boolean) {
        Toast.makeText(this, "Counting bots…", Toast.LENGTH_SHORT).show()
        bg.execute {
            var rakazoBots = emptyList<BotItem>()
            var mausBots = emptyList<BotItem>()
            var rakazoNote: String? = null
            var mausNote: String? = null
            var rakazoSpace: String? = SecureStore.get(this, SecureStore.KEY_RAKAZO_SPACE)
            // Rakazo (the Rakazo host).
            val rakazoBase = Prefs.baseUrl(this, ServerKind.RAKAZO)
            val rakazoToken = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN)
            if (rakazoBase == null || rakazoToken == null) {
                rakazoNote = "Rakazo not signed in — no DMs there."
            } else {
                try {
                    val client = RakazoClient(rakazoBase)
                    val (spaceId, bots) = client.botsFromSpaces(client.spacesList(rakazoToken))
                    if (spaceId != null) {
                        rakazoSpace = spaceId
                        SecureStore.put(this, SecureStore.KEY_RAKAZO_SPACE, spaceId)
                    }
                    rakazoBots = bots
                } catch (e: Exception) {
                    rakazoNote = "Rakazo unreachable (${e.message}) — no DMs there."
                }
            }
            // MausBot (the Rakazo host or the OpenMausBot host — whichever address is in Settings).
            val mausBase = Prefs.baseUrl(this, ServerKind.MAUSBOT)
            val mausToken = SecureStore.get(this, SecureStore.KEY_MAUSBOT_TOKEN)
            if (mausBase == null || mausToken == null) {
                mausNote = "MausBot not paired — no DMs there."
            } else {
                try {
                    mausBots = MausBotClient(mausBase).listBots(mausToken)
                } catch (e: Exception) {
                    mausNote = "MausBot unreachable (${e.message}) — no DMs there."
                }
            }
            val mausDmable = mausBots.count { it.threadId != null }
            val skipped = mausBots.size - mausDmable
            val total = rakazoBots.size + mausDmable
            runOnUiThread {
                val lines = ArrayList<String>()
                if (hubAvailable) {
                    lines += "This posts to #general on the hub and also sends a direct message to $total bot${if (total == 1) "" else "s"}:"
                } else {
                    lines += "The hub can't be reached, so no #general post — this sends a direct message to $total bot${if (total == 1) "" else "s"}:"
                }
                // Server names from the addresses in Settings (the host
                // decides) — a MausBot on the Rakazo host says the Rakazo host.
                lines += "• ${rakazoBots.size} on ${serverNameFromUrl(rakazoBase, ServerKind.RAKAZO)} (Rakazo)"
                lines += "• $mausDmable on ${serverNameFromUrl(mausBase, ServerKind.MAUSBOT)} (MausBot)"
                if (skipped > 0) lines += "• $skipped MausBot bot${if (skipped == 1) "" else "s"} skipped (no chat thread yet)"
                if (rakazoNote != null) lines += rakazoNote
                if (mausNote != null) lines += mausNote
                AlertDialog.Builder(this)
                    .setTitle("Send @everyone?")
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton("Send to all") { _, _ ->
                        sendEveryoneBroadcast(text, hubAvailable, rakazoToken, rakazoSpace, rakazoBots, mausToken, mausBots)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /**
     * Hub post first (only when the hub is up), then a DM to every bot
     * on both servers (the raw text — the @everyone tag means nothing
     * in a 1:1 chat). The DMs go out even when the hub post fails or
     * is skipped: a dead hub must not silence the broadcast. One bot
     * failing never stops the rest; the toast reports both parts.
     */
    private fun sendEveryoneBroadcast(
        text: String,
        hubAvailable: Boolean,
        rakazoToken: String?, rakazoSpace: String?, rakazoBots: List<BotItem>,
        mausToken: String?, mausBots: List<BotItem>
    ) {
        val pair = hub()
        bg.execute {
            var hubPosted = false
            var hubError: String? = null // null = hub post skipped, not attempted
            if (hubAvailable && pair != null) {
                val (base, key) = pair
                try {
                    HubClient(base).postRoom(key, "general", "@everyone $text", null)
                    hubPosted = true
                } catch (e: Exception) {
                    hubError = e.message ?: "hub unreachable"
                }
            }
            var sent = 0
            var failed = 0
            var firstError: String? = null
            // Rakazo DMs: threads/send to each bot's main thread.
            if (rakazoToken != null && rakazoSpace != null) {
                val rakazoBase = Prefs.baseUrl(this, ServerKind.RAKAZO)
                if (rakazoBase != null) {
                    val client = RakazoClient(rakazoBase)
                    for (bot in rakazoBots) {
                        try {
                            client.threadSend(rakazoToken, rakazoSpace, bot.id, text)
                            sent++
                        } catch (e: Exception) {
                            failed++
                            if (firstError == null) firstError = "${bot.name}: ${e.message}"
                        }
                    }
                }
            }
            // MausBot DMs: POST /api/bots/:id/messages on the main thread.
            if (mausToken != null) {
                val mausBase = Prefs.baseUrl(this, ServerKind.MAUSBOT)
                if (mausBase != null) {
                    val client = MausBotClient(mausBase)
                    for (bot in mausBots) {
                        val threadId = bot.threadId ?: continue
                        try {
                            client.send(mausToken, bot.id, threadId, text)
                            sent++
                        } catch (e: Exception) {
                            failed++
                            if (firstError == null) firstError = "${bot.name}: ${e.message}"
                        }
                    }
                }
            }
            runOnUiThread {
                val hubPart = when {
                    hubPosted -> "Sent to #general"
                    hubError != null -> "Hub post failed ($hubError)"
                    else -> "Hub post skipped (hub unreachable)"
                }
                val msg = if (failed == 0) {
                    "$hubPart and DMs sent to $sent bot${if (sent == 1) "" else "s"}."
                } else {
                    "$hubPart — DMs sent to $sent bot${if (sent == 1) "" else "s"}, $failed failed (${firstError ?: "see the servers"})."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun sendBroadcast(tag: String, text: String) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                HubClient(base).postRoom(key, "general", "$tag $text", null)
                runOnUiThread {
                    Toast.makeText(this, "Broadcast sent to $tag in #general.", Toast.LENGTH_LONG).show()
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Broadcast failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Start a new DM: pick a member, open the thread, jump into it. */
    private fun newDm() {
        val pair = hub()
        if (pair == null) {
            Toast.makeText(this, "Add the hub address and your key in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        val (base, key) = pair
        Toast.makeText(this, "Loading members…", Toast.LENGTH_SHORT).show()
        bg.execute {
            try {
                val names = HubClient(base).memberNames(key)
                runOnUiThread {
                    if (names.isEmpty()) {
                        Toast.makeText(this, "No members found.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Message whom?")
                        .setItems(names.toTypedArray()) { _, which ->
                            openDmWith(names[which])
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

    private fun openDmWith(member: String) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val (room, title) = HubClient(base).openDm(key, member)
                runOnUiThread {
                    startActivity(
                        Intent(this, HubRoomActivity::class.java).apply {
                            putExtra("room", room)
                            putExtra("title", title)
                            putExtra("isDm", true)
                        }
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not open that DM.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun initials(name: String): String {
        val clean = name.trimStart('#').trim()
        val parts = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "?"
        val first = parts[0].firstOrNull()?.uppercaseChar() ?: return "?"
        val second = if (parts.size > 1) parts[1].firstOrNull()?.uppercaseChar() else null
        return "" + first + (second ?: "")
    }

    private fun avatarColor(name: String): Int {
        val palette = intArrayOf(
            Color.parseColor("#1E8A9B"), Color.parseColor("#7A5C2E"),
            Color.parseColor("#3E6B4F"), Color.parseColor("#8A4B2E"),
            Color.parseColor("#4A5A8A"), Color.parseColor("#8A2E5C")
        )
        return palette[Math.abs(name.hashCode()) % palette.size]
    }

    private inner class HubAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Room -> 1
            is Row.PrivateToggle -> 2
            is Row.HiddenDms -> 3
        }
        override fun isEnabled(position: Int) = rows[position] is Row.Room
            || rows[position] is Row.PrivateToggle
            || rows[position] is Row.HiddenDms

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is Row.Header) {
                val tv = convertView as? TextView ?: TextView(this@HubActivity).apply {
                    setPadding(16, 18, 16, 6)
                    setTextColor(Color.parseColor("#12263F"))
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                tv.text = row.text
                return tv
            }
            if (row is Row.PrivateToggle) {
                val tv = convertView as? TextView ?: TextView(this@HubActivity).apply {
                    setPadding(16, 14, 16, 14)
                    setTextColor(Color.parseColor("#1E8A9B"))
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                tv.text = if (row.expanded) "▾  Private rooms (${row.count})" else "▸  Private rooms (${row.count})"
                return tv
            }
            if (row is Row.HiddenDms) {
                val tv = convertView as? TextView ?: TextView(this@HubActivity).apply {
                    setPadding(16, 14, 16, 14)
                    setTextColor(Color.parseColor("#5A6B7C"))
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                tv.text = "▸  Hidden DMs (${row.count}) — tap to see them"
                return tv
            }
            val item = (row as Row.Room).item
            val view = convertView
                ?: LayoutInflater.from(this@HubActivity).inflate(R.layout.item_bot, parent, false)
            val tvAvatar = view.findViewById<TextView>(R.id.tvAvatar)
            val tvName = view.findViewById<TextView>(R.id.tvName)
            val tvSub = view.findViewById<TextView>(R.id.tvSub)
            tvAvatar.text = initials(item.title)
            tvAvatar.backgroundTintList = ColorStateList.valueOf(avatarColor(item.title))
            tvName.text = item.title
            val sub = when {
                item.lastText != null && item.lastAuthor != null -> "${item.lastAuthor}: ${item.lastText}"
                item.topic.isNotEmpty() -> item.topic
                item.count > 0 -> "${item.count} messages"
                else -> ""
            }
            val shown = if (item.isPrivate && !item.isDm) {
                if (sub.isEmpty()) "Private" else "Private · $sub"
            } else sub
            tvSub.text = shown
            tvSub.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            return view
        }
    }
}
