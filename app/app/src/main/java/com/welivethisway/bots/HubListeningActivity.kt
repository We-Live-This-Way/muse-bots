package com.welivethisway.bots

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.ApiException
import com.welivethisway.bots.api.HubClient
import com.welivethisway.bots.api.HubMember
import com.welivethisway.bots.api.HubRoomItem
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.SecureStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Listening (Hub → Admin → Listening). Some bots always listen in a room
 * — they wake on EVERY post there, no @mention needed — while the rest
 * only answer when called. The hub enforces who can listen (hub.js
 * setListener): bots only, wake-up on, linked to an OpenMausBot bot, in a
 * members-only room, and on the roster for private rooms. When a bot
 * can't listen the screen says why in plain words, and offers the fix
 * (turn wake on / add to the roster) where the hub lets the app do it.
 * Non-admin keys get the same plain message as the other admin tools.
 */
class HubListeningActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var tvStatus: TextView
    private lateinit var spRoom: Spinner
    private lateinit var tvRoomNote: TextView
    private lateinit var llBots: LinearLayout
    private lateinit var tvNoBots: TextView

    private var isAdmin: Boolean? = null
    private var myName: String? = null
    private var rooms: List<HubRoomItem> = emptyList()
    private var bots: List<HubMember> = emptyList()
    private var selectedRoom: HubRoomItem? = null
    private var spinnerReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub_listening)
        title = "Listening"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        tvStatus = findViewById(R.id.tvListenStatus)
        spRoom = findViewById(R.id.spRoom)
        tvRoomNote = findViewById(R.id.tvRoomNote)
        llBots = findViewById(R.id.llBots)
        tvNoBots = findViewById(R.id.tvNoBots)
        spRoom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (!spinnerReady) return
                selectedRoom = rooms.getOrNull(pos)
                renderBots()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        checkAdminAndLoad()
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

    private fun checkAdminAndLoad() {
        val pair = hub()
        if (pair == null) {
            tvStatus.text = "Not set up — add the hub address and your member key in Settings."
            tvNoBots.text = ""
            return
        }
        val (base, key) = pair
        tvStatus.text = "Checking…"
        bg.execute {
            try {
                val me = HubClient(base).me(key)
                myName = me.name
                isAdmin = me.isAdmin
                if (!me.isAdmin) {
                    runOnUiThread {
                        tvStatus.text = "Admin tools need the hub owner's key — this key is signed in as ${me.name}."
                        tvNoBots.text = ""
                    }
                    return@execute
                }
                val client = HubClient(base)
                val overview = client.overview(key)
                val members = client.adminMembers(key)
                runOnUiThread {
                    tvStatus.text = "Signed in as ${me.name} (hub owner)."
                    rooms = overview.rooms.sortedBy { it.room }
                    bots = members.filter { it.kind == "bot" && !it.guest }
                    setupSpinner()
                }
            } catch (e: ApiException) {
                if (e.code == 401) SecureStore.remove(this, SecureStore.KEY_HUB_KEY)
                if (handle403(e)) return@execute
                runOnUiThread {
                    tvStatus.text = e.message ?: "Could not load."
                    Toast.makeText(this, e.message ?: "Could not load.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Unreachable (${e.message})" }
            }
        }
    }

    private fun setupSpinner() {
        if (rooms.isEmpty()) {
            tvNoBots.text = "No rooms on the hub yet."
            return
        }
        val names = rooms.map { "#${it.room}" }
        spRoom.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerReady = true
        selectedRoom = rooms.firstOrNull()
        renderBots()
    }

    /** Why this bot can't listen in this room, or null when it can. */
    private fun blockReason(room: HubRoomItem, bot: HubMember): String? {
        if (!bot.wake) return "wake off"
        if (bot.ombBotId.isNullOrEmpty()) return "not linked"
        if (room.isPrivate && room.roster != null && bot.name !in room.roster) return "not on roster"
        return null
    }

    private fun renderBots() {
        val room = selectedRoom
        llBots.removeAllViews()
        if (room == null) {
            tvRoomNote.text = ""
            tvNoBots.text = "No room picked."
            tvNoBots.visibility = View.VISIBLE
            return
        }
        if (room.visitors) {
            tvRoomNote.text = "#${room.room} is open to visitors — listeners only work in members-only rooms, so no bot can always listen here."
            tvNoBots.text = ""
            tvNoBots.visibility = View.GONE
            return
        }
        tvRoomNote.text = if (room.isPrivate) {
            "#${room.room} is private — only bots on its roster can listen here."
        } else {
            "#${room.room} is members-only — bots below can always listen here."
        }
        if (bots.isEmpty()) {
            tvNoBots.text = "No bots on the hub yet — add one from Admin first."
            tvNoBots.visibility = View.VISIBLE
            return
        }
        tvNoBots.visibility = View.GONE
        val listening = room.listeners.toSet()
        for (bot in bots.sortedBy { it.name }) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_bot, llBots, false)
            val tvName = row.findViewById<TextView>(R.id.tvName)
            val tvSub = row.findViewById<TextView>(R.id.tvSub)
            tvName.text = bot.name
            val isListening = bot.name in listening
            val reason = blockReason(room, bot)
            tvSub.text = when {
                isListening -> "Always listens — wakes on every post here"
                reason == "wake off" -> "Can't listen — wake-up is off (tap for the fix)"
                reason == "not linked" -> "Can't listen — not linked to an OpenMausBot bot"
                reason == "not on roster" -> "Can't listen — not on this room's roster (tap for the fix)"
                else -> "Only when @mentioned"
            }
            row.findViewById<TextView>(R.id.tvAvatar).text =
                bot.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val sw = Switch(this)
            // Set the state before attaching the listener, so setup doesn't fire a call.
            sw.isChecked = isListening
            sw.isEnabled = reason == null
            sw.setOnCheckedChangeListener { _, checked ->
                setListening(room, bot, checked)
            }
            (row as LinearLayout).addView(sw)
            row.setOnClickListener { showBotDialog(room, bot, isListening, reason) }
            llBots.addView(row)
        }
    }

    private fun showBotDialog(room: HubRoomItem, bot: HubMember, isListening: Boolean, reason: String?) {
        val (title, message, action) = when {
            isListening -> Triple(
                "${bot.name} always listens here",
                "${bot.name} wakes on every post in #${room.room}, even with no @mention. Turn it off and it only answers when called.",
                "Only when @mentioned"
            )
            reason == "wake off" -> Triple(
                "${bot.name} can't listen yet",
                "Its wake-up is off, and the hub won't let a bot listen until wake-up is on. Turn wake-up on and it can listen here.",
                "Turn wake on"
            )
            reason == "not linked" -> Triple(
                "${bot.name} can't listen here",
                "The hub only lets bots linked to the house OpenMausBot listen in a room, and ${bot.name} isn't linked. Link it in the hub's Manage panel first.",
                null
            )
            reason == "not on roster" -> Triple(
                "${bot.name} isn't on this room's roster",
                "#${room.room} is private, and only bots on its roster can listen there. Add ${bot.name} to the roster and it can listen.",
                "Add to roster"
            )
            else -> Triple(
                "${bot.name} only answers when called",
                "${bot.name} wakes when someone @mentions it in #${room.room}. Make it always listen and it wakes on every post here.",
                "Always listen"
            )
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
        if (action != null) {
            builder.setPositiveButton(action) { _, _ ->
                when (reason) {
                    "wake off" -> turnWakeOn(bot)
                    "not on roster" -> addToRoster(room, bot)
                    else -> setListening(room, bot, !isListening)
                }
            }
        }
        builder.show()
    }

    private fun setListening(room: HubRoomItem, bot: HubMember, on: Boolean) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                HubClient(base).setListener(key, room.room, bot.name, on)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (on) "${bot.name} now always listens in #${room.room}."
                        else "${bot.name} now only answers when @mentioned in #${room.room}.",
                        Toast.LENGTH_SHORT
                    ).show()
                    reloadRooms()
                }
            } catch (e: ApiException) {
                if (handle403(e)) return@execute
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "That didn't work.", Toast.LENGTH_LONG).show()
                    renderBots()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "That didn't work.", Toast.LENGTH_LONG).show()
                    renderBots()
                }
            }
        }
    }

    private fun turnWakeOn(bot: HubMember) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                HubClient(base).setMemberWake(key, bot.id, true)
                runOnUiThread {
                    Toast.makeText(this, "${bot.name}'s wake-up is on — it can listen now.", Toast.LENGTH_SHORT).show()
                    reloadAll()
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

    private fun addToRoster(room: HubRoomItem, bot: HubMember) {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                HubClient(base).setRoster(key, room.room, bot.name, true)
                runOnUiThread {
                    Toast.makeText(this, "${bot.name} added to #${room.room}'s roster.", Toast.LENGTH_SHORT).show()
                    reloadRooms()
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

    /** Re-reads rooms so the toggles show the hub's true state after a change. */
    private fun reloadRooms() {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val overview = HubClient(base).overview(key)
                runOnUiThread {
                    val current = selectedRoom?.room
                    rooms = overview.rooms.sortedBy { it.room }
                    selectedRoom = rooms.find { it.room == current } ?: rooms.firstOrNull()
                    renderBots()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't refresh — reopen the screen to see the change.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Re-reads rooms and members (after a wake-up change). */
    private fun reloadAll() {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val client = HubClient(base)
                val overview = client.overview(key)
                val members = client.adminMembers(key)
                runOnUiThread {
                    val current = selectedRoom?.room
                    rooms = overview.rooms.sortedBy { it.room }
                    bots = members.filter { it.kind == "bot" && !it.guest }
                    selectedRoom = rooms.find { it.room == current } ?: rooms.firstOrNull()
                    renderBots()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't refresh — reopen the screen to see the change.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
