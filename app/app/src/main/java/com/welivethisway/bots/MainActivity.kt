package com.welivethisway.bots

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.welivethisway.bots.api.ApiException
import com.welivethisway.bots.api.AvatarLoader
import com.welivethisway.bots.api.BotAvatarView
import com.welivethisway.bots.api.BotItem
import com.welivethisway.bots.api.HubClient
import com.welivethisway.bots.api.MausBotClient
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.Prefs.BotSort
import com.welivethisway.bots.api.RakazoClient
import com.welivethisway.bots.api.SecureStore
import com.welivethisway.bots.api.ServerKind
import com.welivethisway.bots.api.lastOrderSubtitle
import com.welivethisway.bots.api.serverLabelForMachine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One list with the bots from both servers. No server badges — just bots. */
class MainActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var listBots: ListView
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private val bots = ArrayList<BotItem>()
    private lateinit var adapter: BotAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listBots = findViewById(R.id.listBots)
        tvStatus = findViewById(R.id.tvStatus)
        tvEmpty = findViewById(R.id.tvEmpty)
        adapter = BotAdapter()
        listBots.adapter = adapter
        listBots.setOnItemClickListener { _, _, pos, _ ->
            val bot = bots[pos]
            startActivity(
                Intent(this, ChatActivity::class.java).apply {
                    putExtra("kind", bot.kind.name)
                    putExtra("botId", bot.id)
                    putExtra("botName", bot.name)
                    putExtra("threadId", bot.threadId)
                    // The chat header shows the same avatar as the list.
                    putExtra("colorHex", bot.colorHex)
                    putExtra("avatarSet", bot.avatarSet)
                    putExtra("avatarUrl", bot.avatarUrl)
                    putExtra("avatarCrop", bot.avatarCrop)
                    putExtra("avatarZoom", bot.avatarZoom)
                    putExtra("avatarFocusX", bot.avatarFocusX)
                    putExtra("avatarFocusY", bot.avatarFocusY)
                    putExtra("mascotBody", bot.mascotBody)
                    putExtra("mascotFaceColor", bot.mascotFaceColor)
                }
            )
        }
        val spSort = findViewById<Spinner>(R.id.spSort)
        val sortLabels = listOf("Pinned first", "Most recent", "Name (A–Z)")
        spSort.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spSort.setSelection(
            when (Prefs.botSort(this)) {
                BotSort.PINNED_RECENT -> 0
                BotSort.RECENT -> 1
                BotSort.NAME -> 2
            }
        )
        spSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val sort = when (position) {
                    0 -> BotSort.PINNED_RECENT
                    1 -> BotSort.RECENT
                    else -> BotSort.NAME
                }
                if (sort != Prefs.botSort(this@MainActivity)) {
                    Prefs.setBotSort(this@MainActivity, sort)
                    sortBots()
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnHub).setOnClickListener {
            startActivity(Intent(this, HubActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

    private fun refresh() {
        tvStatus.text = "Loading…"
        bg.execute {
            // Status lines never name a server — the list is just bots.
            val problems = ArrayList<String>()
            var totalBots = 0
            val all = ArrayList<BotItem>()
            var rakazoToken: String? = null
            var rakazoSpaceId: String? = null

            // Rakazo (the Rakazo host, via its Cloudflare tunnel address).
            val rakazoBase = Prefs.baseUrl(this, ServerKind.RAKAZO)
            if (rakazoBase == null) {
                problems += "Not set up — add the address in Settings"
            } else {
                val token = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN)
                if (token == null) {
                    problems += "Not signed in — sign in from Settings"
                } else {
                    rakazoToken = token
                    try {
                        val client = RakazoClient(rakazoBase)
                        val (spaceId, rb) = client.botsFromSpaces(client.spacesList(token))
                        if (spaceId != null) {
                            rakazoSpaceId = spaceId
                            SecureStore.put(this, SecureStore.KEY_RAKAZO_SPACE, spaceId)
                        }
                        all += rb
                        totalBots += rb.size
                    } catch (e: ApiException) {
                        if (e.code == 401) {
                            SecureStore.remove(this, SecureStore.KEY_RAKAZO_TOKEN)
                            problems += "Session expired — sign in again from Settings"
                        } else {
                            problems += e.message ?: "Request failed"
                        }
                    } catch (e: Exception) {
                        problems += "Unreachable (${e.message})"
                    }
                }
            }

            // MausBot (the Rakazo host or the OpenMausBot host — whichever address is in Settings).
            val mausbotBase = Prefs.baseUrl(this, ServerKind.MAUSBOT)
            if (mausbotBase == null) {
                problems += "Not set up — add the address in Settings"
            } else {
                val token = SecureStore.get(this, SecureStore.KEY_MAUSBOT_TOKEN)
                if (token == null) {
                    problems += "Not paired — pair from Settings"
                } else {
                    try {
                        val mb = MausBotClient(mausbotBase).listBots(token)
                        all += mb
                        totalBots += mb.size
                    } catch (e: ApiException) {
                        if (e.code == 401) {
                            SecureStore.remove(this, SecureStore.KEY_MAUSBOT_TOKEN)
                            problems += "Pairing expired — pair again from Settings"
                        } else {
                            problems += e.message ?: "Request failed"
                        }
                    } catch (e: Exception) {
                        problems += "Unreachable (${e.message})"
                    }
                }
            }

            val lines = ArrayList<String>()
            if (totalBots > 0) lines += "$totalBots bot${if (totalBots == 1) "" else "s"}"
            lines += problems.distinct()

            runOnUiThread {
                bots.clear()
                bots.addAll(all)
                sortBots()
                adapter.notifyDataSetChanged()
                tvStatus.text = lines.joinToString("\n")
                val empty = bots.isEmpty()
                tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                listBots.visibility = if (empty) View.GONE else View.VISIBLE
            }
            // Working dots and Rakazo subtitles fill in after the list
            // is on screen: one threads/get per Rakazo bot (its snapshot
            // carries the active run and the thread history). MausBot
            // subtitles already ride in the roster call. Server names
            // get corrected from the Hub after that.
            refreshWorking(rakazoToken, rakazoSpaceId)
            refreshServerNames()
        }
    }

    /**
     * Fills in the Rakazo working dots and subtitles. Runs after the list
     * is shown, so a slow server never holds up the list itself. A failed
     * check leaves the dot and the subtitle as they are — a failed check
     * is not "idle", and the description stays as the subtitle fallback.
     */
    private fun refreshWorking(token: String?, spaceId: String?) {
        if (token == null || spaceId == null) return
        val base = Prefs.baseUrl(this, ServerKind.RAKAZO) ?: return
        val targets = bots.filter { it.kind == ServerKind.RAKAZO }
        if (targets.isEmpty()) return
        val client = RakazoClient(base)
        var changed = false
        for (bot in targets) {
            try {
                val snapshot = client.threadGet(token, spaceId, bot.id)
                val working = client.isWorking(snapshot)
                if (bot.working != working) {
                    bot.working = working
                    changed = true
                }
                // The same snapshot carries the thread: the last thing
                // the operator said to this bot becomes the line under its name.
                val (msgs, _) = client.parseMessages(snapshot)
                val last = msgs.lastOrNull { it.mine && it.text.isNotBlank() }?.text
                val sub = lastOrderSubtitle(last)
                if (sub.isNotEmpty() && bot.subtitle != sub) {
                    bot.subtitle = sub
                    changed = true
                }
            } catch (e: Exception) {
                // Leave the dot and subtitle as they are.
            }
        }
        if (changed) runOnUiThread { adapter.notifyDataSetChanged() }
    }

    /**
     * Fixes the server name in the detail line from the Hub, which knows
     * each bot's real machine. The address typed in Settings can be a
     * tunnel or an old name — the operator's MausBot bots showed "the OpenMausBot host"
     * while running on the Rakazo host. Runs after the list is shown; no Hub
     * set up, a down Hub, or a failed call leaves the URL-derived name.
     */
    private fun refreshServerNames() {
        val base = Prefs.hubBaseUrl(this) ?: return
        val key = SecureStore.get(this, SecureStore.KEY_HUB_KEY) ?: return
        val targets = bots.filter { it.kind == ServerKind.MAUSBOT }
        if (targets.isEmpty()) return
        var changed = false
        try {
            val byBotId = HubClient(base).adminMembers(key)
                .mapNotNull { m ->
                    val label = serverLabelForMachine(m.machine)
                    if (m.ombBotId != null && label != null) m.ombBotId to label else null
                }.toMap()
            for (bot in targets) {
                val label = byBotId[bot.id] ?: continue
                if (bot.serverNameOverride != label) {
                    bot.serverNameOverride = label
                    changed = true
                }
            }
        } catch (e: Exception) {
            // Keep the URL-derived name.
        }
        if (changed) runOnUiThread { adapter.notifyDataSetChanged() }
    }

    /** Orders the list by the saved sort choice. */
    private fun sortBots() {
        when (Prefs.botSort(this)) {
            BotSort.NAME -> bots.sortBy { it.name.lowercase() }
            BotSort.RECENT -> bots.sortWith(
                compareByDescending<BotItem> { it.activityAt ?: 0 }
                    .thenBy { it.name.lowercase() }
            )
            BotSort.PINNED_RECENT -> bots.sortWith(
                compareByDescending<BotItem> { it.pinned }
                    .thenByDescending { it.activityAt ?: 0 }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    private inner class BotAdapter : BaseAdapter() {
        override fun getCount() = bots.size
        override fun getItem(position: Int) = bots[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_bot, parent, false)
            val bot = bots[position]
            val avatar = view.findViewById<BotAvatarView>(R.id.vAvatar)
            val tvAvatar = view.findViewById<TextView>(R.id.tvAvatar)
            avatar.visibility = View.VISIBLE
            tvAvatar.visibility = View.GONE
            AvatarLoader.load(this@MainActivity, bot, avatar)
            val tvName = view.findViewById<TextView>(R.id.tvName)
            val tvSub = view.findViewById<TextView>(R.id.tvSub)
            // the operator's detail line: `Name - Server type - Model - Server name`.
            tvName.text = if (bot.unread) "${bot.detailLine}  •" else bot.detailLine
            tvSub.text = bot.subtitle
            tvSub.visibility = if (bot.subtitle.isEmpty()) View.GONE else View.VISIBLE
            // Working dot: green while the bot has an active run, grey idle.
            val dot = view.findViewById<View>(R.id.vWorking)
            dot.visibility = View.VISIBLE
            dot.backgroundTintList = ColorStateList.valueOf(
                if (bot.working) Color.parseColor("#2E9E5B") else Color.parseColor("#9AA5B1")
            )
            return view
        }
    }
}
