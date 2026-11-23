package com.welivethisway.bots

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.ApiException
import com.welivethisway.bots.api.HubClient
import com.welivethisway.bots.api.HubMessage
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.SecureStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One hub room (or DM thread). Read + post. Poll-based: loads on open,
 * on resume, on Refresh and after sending — the hub has no live stream.
 * @ opens a picker of @groups and members (the server expands @group
 * tags on post). Tap a message to reply to it. Messages that @mention
 * you are marked.
 */
class HubRoomActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var listMessages: ListView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var tvLive: TextView
    private lateinit var rowReply: LinearLayout
    private lateinit var tvReply: TextView
    private val messages = ArrayList<HubMessage>()
    private lateinit var adapter: MsgAdapter

    private lateinit var room: String
    private lateinit var roomTitle: String
    private var isDm = false
    private var myName: String? = null
    private var replyToId: Long? = null
    private var replyToAuthor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub_room)
        room = intent.getStringExtra("room") ?: ""
        roomTitle = intent.getStringExtra("title") ?: room
        isDm = intent.getBooleanExtra("isDm", false)
        title = roomTitle
        actionBar?.setDisplayHomeAsUpEnabled(true)

        listMessages = findViewById(R.id.listMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        tvLive = findViewById(R.id.tvLive)
        rowReply = findViewById(R.id.rowReply)
        tvReply = findViewById(R.id.tvReply)
        adapter = MsgAdapter()
        listMessages.adapter = adapter

        listMessages.setOnItemClickListener { _, _, pos, _ ->
            val msg = messages[pos]
            replyToId = msg.id
            replyToAuthor = msg.author
            tvReply.text = "Replying to ${msg.author}"
            rowReply.visibility = View.VISIBLE
            etMessage.requestFocus()
        }
        findViewById<Button>(R.id.btnCancelReply).setOnClickListener { clearReply() }
        findViewById<Button>(R.id.btnMention).setOnClickListener { showMentionPicker() }
        btnSend.setOnClickListener { send() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdown()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Refresh")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            1 -> {
                load()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun clearReply() {
        replyToId = null
        replyToAuthor = null
        rowReply.visibility = View.GONE
    }

    private fun hub(): Pair<String, String>? {
        val base = Prefs.hubBaseUrl(this) ?: return null
        val key = SecureStore.get(this, SecureStore.KEY_HUB_KEY) ?: return null
        return base to key
    }

    private fun load() {
        val pair = hub()
        if (pair == null) {
            tvLive.text = "Hub not set up — add the address and key in Settings."
            return
        }
        val (base, key) = pair
        tvLive.text = "Loading…"
        bg.execute {
            try {
                val client = HubClient(base)
                if (myName == null) {
                    myName = try {
                        client.me(key).name
                    } catch (e: Exception) {
                        null
                    }
                }
                val list = client.readRoom(key, room)
                runOnUiThread {
                    val atBottom = listMessages.lastVisiblePosition >= messages.size - 1
                    messages.clear()
                    messages.addAll(list)
                    adapter.notifyDataSetChanged()
                    if (atBottom && messages.isNotEmpty()) listMessages.setSelection(messages.size - 1)
                    tvLive.text = if (messages.isEmpty()) "No messages yet." else "Loaded"
                }
            } catch (e: ApiException) {
                if (e.code == 401) SecureStore.remove(this, SecureStore.KEY_HUB_KEY)
                runOnUiThread {
                    tvLive.text = e.message ?: "Could not load."
                    Toast.makeText(this, e.message ?: "Could not load.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvLive.text = "Unreachable (${e.message})"
                }
            }
        }
    }

    private fun send() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        val pair = hub() ?: return
        val (base, key) = pair
        etMessage.setText("")
        btnSend.isEnabled = false
        val replyId = replyToId
        clearReply()
        bg.execute {
            try {
                HubClient(base).postRoom(key, room, text, replyId)
                runOnUiThread { btnSend.isEnabled = true }
                load()
            } catch (e: Exception) {
                runOnUiThread {
                    btnSend.isEnabled = true
                    Toast.makeText(this, e.message ?: "Send failed.", Toast.LENGTH_LONG).show()
                    etMessage.setText(text)
                }
            }
        }
    }

    /** @ picker: groups first, then members. The server expands @group on post. */
    private fun showMentionPicker() {
        val pair = hub() ?: return
        val (base, key) = pair
        bg.execute {
            try {
                val client = HubClient(base)
                val groups = client.groups(key).map { "@${it.name}" }
                val names = client.memberNames(key).map { "@$it" }
                val items = (groups + names).distinct()
                runOnUiThread {
                    if (items.isEmpty()) {
                        Toast.makeText(this, "No groups or members found.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Mention")
                        .setItems(items.toTypedArray()) { _, which ->
                            val cur = etMessage.text.toString()
                            val insert = items[which] + " "
                            val pos = etMessage.selectionStart.coerceIn(0, cur.length)
                            etMessage.setText(cur.substring(0, pos) + insert + cur.substring(pos))
                            etMessage.setSelection((pos + insert.length).coerceAtMost(etMessage.text.length))
                            etMessage.requestFocus()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Could not load mentions.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private inner class MsgAdapter : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = messages[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@HubRoomActivity).inflate(R.layout.item_hub_message, parent, false)
            val msg = messages[position]
            val tvAuthor = view.findViewById<TextView>(R.id.tvAuthor)
            val tvBody = view.findViewById<TextView>(R.id.tvBody)
            val tvTime = view.findViewById<TextView>(R.id.tvTime)
            val mine = myName != null && msg.author == myName
            val mentioned = myName != null && msg.mentions.any { it.equals(myName, ignoreCase = true) }
            tvAuthor.text = when {
                mine -> "You"
                mentioned -> "${msg.author}  •  mentioned you"
                msg.visitor -> "${msg.author} (visitor)"
                else -> msg.author
            }
            tvAuthor.setTextColor(
                if (mentioned) Color.parseColor("#1E8A9B") else Color.parseColor("#5A6B7D")
            )
            val body = StringBuilder()
            if (msg.replyTo != null) body.append("↩ reply\n")
            body.append(msg.text)
            if (msg.fileName != null) body.append("\n📎 ${msg.fileName}")
            if (msg.edited) body.append("  (edited)")
            tvBody.text = body.toString()
            val bodyParams = tvBody.layoutParams as LinearLayout.LayoutParams
            val timeParams = tvTime.layoutParams as LinearLayout.LayoutParams
            val authorParams = tvAuthor.layoutParams as LinearLayout.LayoutParams
            if (mine) {
                bodyParams.gravity = android.view.Gravity.END
                timeParams.gravity = android.view.Gravity.END
                authorParams.gravity = android.view.Gravity.END
                tvBody.setBackgroundResource(R.drawable.bubble_mine)
                tvBody.setTextColor(Color.WHITE)
            } else {
                bodyParams.gravity = android.view.Gravity.START
                timeParams.gravity = android.view.Gravity.START
                authorParams.gravity = android.view.Gravity.START
                tvBody.setBackgroundResource(R.drawable.bubble_bot)
                tvBody.setTextColor(Color.parseColor("#12263F"))
            }
            tvBody.layoutParams = bodyParams
            tvTime.layoutParams = timeParams
            tvAuthor.layoutParams = authorParams
            if (msg.atMs != null) {
                tvTime.visibility = View.VISIBLE
                tvTime.text = TIME_FMT.format(Date(msg.atMs))
            } else {
                tvTime.visibility = View.GONE
            }
            return view
        }
    }

    companion object {
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
