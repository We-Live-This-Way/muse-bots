package com.welivethisway.bots

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.welivethisway.bots.api.ApiException
import com.welivethisway.bots.api.AvatarLoader
import com.welivethisway.bots.api.BotAvatarView
import com.welivethisway.bots.api.BotItem
import com.welivethisway.bots.api.ChatImage
import com.welivethisway.bots.api.ChatMsg
import com.welivethisway.bots.api.ImageFetch
import com.welivethisway.bots.api.ImageStore
import com.welivethisway.bots.api.MausBotClient
import com.welivethisway.bots.api.Prefs
import com.welivethisway.bots.api.RakazoClient
import com.welivethisway.bots.api.SecureStore
import com.welivethisway.bots.api.ServerKind
import com.welivethisway.bots.api.decodeSampled
import com.welivethisway.bots.api.imageCacheKey
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One chat thread. History loads once, then an SSE stream re-fetches on events. */
class ChatActivity : Activity() {

    private val bg: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var listMessages: ListView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAttach: Button
    private lateinit var tvLive: TextView
    private lateinit var pendingRow: LinearLayout
    private lateinit var ivPending: ImageView
    private lateinit var btnRemovePending: Button
    private val messages = ArrayList<ChatMsg>()
    private lateinit var adapter: MsgAdapter

    /** An image picked but not yet sent (with optional caption in the box). */
    private var pendingImage: PendingImage? = null

    /**
     * Images this app just sent. Kept in memory only, for this chat session:
     * the server is the source of truth, but a MausBot user message may not
     * read back with its attachments, so the echo keeps the image visible.
     * An echo is dropped once the server shows the same message with images.
     */
    private val localEchoes = ArrayList<ChatMsg>()

    private lateinit var kind: ServerKind
    private lateinit var botId: String
    private var threadId: String? = null
    private lateinit var botName: String
    private var cursor: Long = -1

    @Volatile
    private var sseStopped = false

    @Volatile
    private var sseConn: HttpURLConnection? = null
    private var refetchPending = false
    private var fetchInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        kind = ServerKind.valueOf(intent.getStringExtra("kind") ?: "RAKAZO")
        botId = intent.getStringExtra("botId") ?: ""
        botName = intent.getStringExtra("botName") ?: "Bot"
        threadId = intent.getStringExtra("threadId")
        title = botName
        // The bot's avatar next to its name in the header.
        val headerBot = BotItem(
            kind = kind,
            id = botId,
            name = botName,
            subtitle = "",
            colorHex = intent.getStringExtra("colorHex"),
            unread = false,
            threadId = threadId,
            preview = null,
            avatarSet = intent.getStringExtra("avatarSet"),
            avatarUrl = intent.getStringExtra("avatarUrl"),
            avatarCrop = intent.getStringExtra("avatarCrop"),
            avatarZoom = intent.getFloatExtra("avatarZoom", 1f),
            avatarFocusX = intent.getFloatExtra("avatarFocusX", 0.5f),
            avatarFocusY = intent.getFloatExtra("avatarFocusY", 0.5f),
            mascotBody = intent.getStringExtra("mascotBody"),
            mascotFaceColor = intent.getStringExtra("mascotFaceColor")
        )
        actionBar?.let { bar ->
            bar.setDisplayShowCustomEnabled(true)
            bar.setDisplayShowTitleEnabled(false)
            val header = layoutInflater.inflate(R.layout.chat_header, null)
            header.findViewById<TextView>(R.id.tvHeaderName).text = botName
            AvatarLoader.load(this, headerBot, header.findViewById(R.id.vHeaderAvatar))
            bar.customView = header
        }
        actionBar?.setDisplayHomeAsUpEnabled(true)

        listMessages = findViewById(R.id.listMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        tvLive = findViewById(R.id.tvLive)
        pendingRow = findViewById(R.id.pendingRow)
        ivPending = findViewById(R.id.ivPending)
        btnRemovePending = findViewById(R.id.btnRemovePending)
        adapter = MsgAdapter()
        listMessages.adapter = adapter

        btnSend.setOnClickListener { send() }
        btnAttach.setOnClickListener { pickImage() }
        btnRemovePending.setOnClickListener { clearPending() }
        loadHistory()
        startSse()
    }

    override fun onDestroy() {
        super.onDestroy()
        sseStopped = true
        try {
            sseConn?.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        mainHandler.removeCallbacksAndMessages(null)
        bg.shutdown()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setLive(text: String) {
        runOnUiThread { tvLive.text = text }
    }

    private fun rakazoBase() = Prefs.baseUrl(this, ServerKind.RAKAZO)
    private fun mausbotBase() = Prefs.baseUrl(this, ServerKind.MAUSBOT)

    /** Loads the thread once. Returns (messages, working?) or throws. */
    private fun fetchMessages(): Pair<List<ChatMsg>, Boolean> {
        return when (kind) {
            ServerKind.RAKAZO -> {
                val base = rakazoBase() ?: throw ApiException("Rakazo address not set.")
                val token = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN)
                    ?: throw ApiException("Not signed in to Rakazo.")
                val space = SecureStore.get(this, SecureStore.KEY_RAKAZO_SPACE)
                    ?: throw ApiException("No Rakazo space — sign in again from Settings.")
                val client = RakazoClient(base)
                val snapshot = client.threadGet(token, space, botId)
                val (list, cur) = client.parseMessages(snapshot)
                cursor = cur
                list to client.isWorking(snapshot)
            }
            ServerKind.MAUSBOT -> {
                val base = mausbotBase() ?: throw ApiException("MausBot address not set.")
                val token = SecureStore.get(this, SecureStore.KEY_MAUSBOT_TOKEN)
                    ?: throw ApiException("MausBot not paired.")
                val tid = threadId ?: throw ApiException("This bot has no chat thread yet.")
                MausBotClient(base).threadMessages(token, tid) to false
            }
        }
    }

    private fun loadHistory() {
        bg.execute {
            try {
                val (list, working) = fetchMessages()
                runOnUiThread {
                    applyServerList(list)
                    adapter.notifyDataSetChanged()
                    if (working) setLive("Working…") else setLive("Live")
                    if (kind == ServerKind.RAKAZO) markReadSoon()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLive("Could not load chat.")
                    Toast.makeText(this, e.message ?: "Could not load chat.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Replaces the list with the server's messages, then re-appends any
     * local sent-image echoes the server does not show yet. Runs on the
     * UI thread; localEchoes is only touched on the UI thread.
     */
    private fun applyServerList(serverList: List<ChatMsg>) {
        localEchoes.removeAll { echo ->
            serverList.any { s -> s.mine && s.text == echo.text && s.images.isNotEmpty() }
        }
        messages.clear()
        messages.addAll(serverList)
        messages.addAll(localEchoes)
    }

    private fun markReadSoon() {
        bg.execute {
            try {
                val base = rakazoBase() ?: return@execute
                val token = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN) ?: return@execute
                val space = SecureStore.get(this, SecureStore.KEY_RAKAZO_SPACE) ?: return@execute
                RakazoClient(base).markRead(token, space, botId)
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    /** Re-fetch, debounced so a burst of SSE events causes one load. */
    private fun scheduleRefetch() {
        if (refetchPending) return
        refetchPending = true
        mainHandler.postDelayed({
            refetchPending = false
            if (fetchInFlight) return@postDelayed
            fetchInFlight = true
            bg.execute {
                try {
                    val (list, working) = fetchMessages()
                    runOnUiThread {
                        val atBottom = listMessages.lastVisiblePosition >= messages.size - 1
                        applyServerList(list)
                        adapter.notifyDataSetChanged()
                        if (atBottom) listMessages.setSelection(messages.size - 1)
                        if (working) setLive("Working…") else setLive("Live")
                    }
                } catch (e: Exception) {
                    // Keep the old messages; the next event retries.
                } finally {
                    fetchInFlight = false
                }
            }
        }, 500)
    }

    private fun startSse() {
        Thread {
            while (!sseStopped) {
                try {
                    setLive("Live")
                    when (kind) {
                        ServerKind.RAKAZO -> {
                            val base = rakazoBase() ?: return@Thread
                            val token = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN)
                                ?: return@Thread
                            val space = SecureStore.get(this, SecureStore.KEY_RAKAZO_SPACE)
                                ?: return@Thread
                            RakazoClient(base).subscribe(
                                token, space, botId, cursor,
                                { conn -> sseConn = conn },
                                { sseStopped }
                            ) { scheduleRefetch() }
                        }
                        ServerKind.MAUSBOT -> {
                            val base = mausbotBase() ?: return@Thread
                            val token = SecureStore.get(this, SecureStore.KEY_MAUSBOT_TOKEN)
                                ?: return@Thread
                            MausBotClient(base).events(
                                token,
                                { conn -> sseConn = conn },
                                { sseStopped }
                            ) { scheduleRefetch() }
                        }
                    }
                    if (!sseStopped) {
                        setLive("Reconnecting…")
                        Thread.sleep(3000)
                    }
                } catch (e: Exception) {
                    if (!sseStopped) {
                        setLive("Reconnecting…")
                        try {
                            Thread.sleep(3000)
                        } catch (ie: InterruptedException) {
                            // ignore
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun send() {
        val text = etMessage.text.toString().trim()
        val pending = pendingImage
        if (text.isEmpty() && pending == null) return
        etMessage.setText("")
        clearPending()
        btnSend.isEnabled = false
        btnAttach.isEnabled = false
        bg.execute {
            try {
                if (pending != null) sendWithImage(text, pending) else sendText(text)
                runOnUiThread {
                    btnSend.isEnabled = true
                    btnAttach.isEnabled = true
                }
                scheduleRefetch()
                // Safety net: re-fetch once more in case the live stream lags.
                mainHandler.postDelayed({ scheduleRefetch() }, 3000)
            } catch (e: Exception) {
                runOnUiThread {
                    btnSend.isEnabled = true
                    btnAttach.isEnabled = true
                    Toast.makeText(this, e.message ?: "Send failed.", Toast.LENGTH_LONG).show()
                    etMessage.setText(text)
                    // Put the image back so the user can retry instead of re-picking.
                    if (pending != null) setPending(pending)
                }
            }
        }
    }

    private fun sendText(text: String) {
        when (kind) {
            ServerKind.RAKAZO -> {
                val base = rakazoBase() ?: throw ApiException("Rakazo address not set.")
                val token = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN)
                    ?: throw ApiException("Not signed in to Rakazo.")
                val space = SecureStore.get(this, SecureStore.KEY_RAKAZO_SPACE)
                    ?: throw ApiException("No Rakazo space — sign in again from Settings.")
                RakazoClient(base).threadSend(token, space, botId, text)
            }
            ServerKind.MAUSBOT -> {
                val base = mausbotBase() ?: throw ApiException("MausBot address not set.")
                val token = SecureStore.get(this, SecureStore.KEY_MAUSBOT_TOKEN)
                    ?: throw ApiException("MausBot not paired.")
                val tid = threadId ?: throw ApiException("This bot has no chat thread yet.")
                MausBotClient(base).send(token, botId, tid, text)
            }
        }
    }

    /**
     * Sends one image (with the typed text as its caption, if any).
     * Rakazo: upload as an artifact, then send with artifactIds +
     * imageHandling "confirm" so it stays a vision image block.
     * MausBot: upload the raw bytes, then send the text with a standalone
     * <attached-image> tag line carrying the path the upload returned.
     */
    private fun sendWithImage(text: String, pending: PendingImage) {
        val sendNonce = UUID.randomUUID().toString()
        when (kind) {
            ServerKind.RAKAZO -> {
                val base = rakazoBase() ?: throw ApiException("Rakazo address not set.")
                val token = SecureStore.get(this, SecureStore.KEY_RAKAZO_TOKEN)
                    ?: throw ApiException("Not signed in to Rakazo.")
                val space = SecureStore.get(this, SecureStore.KEY_RAKAZO_SPACE)
                    ?: throw ApiException("No Rakazo space — sign in again from Settings.")
                val client = RakazoClient(base)
                val artifactId = client.createArtifact(
                    token, space, botId, pending.name, pending.mime,
                    Base64.encodeToString(pending.bytes, Base64.NO_WRAP), sendNonce
                )
                client.threadSend(token, space, botId, text, listOf(artifactId), "confirm")
            }
            ServerKind.MAUSBOT -> {
                val base = mausbotBase() ?: throw ApiException("MausBot address not set.")
                val token = SecureStore.get(this, SecureStore.KEY_MAUSBOT_TOKEN)
                    ?: throw ApiException("MausBot not paired.")
                val tid = threadId ?: throw ApiException("This bot has no chat thread yet.")
                val client = MausBotClient(base)
                val path = client.uploadAttachment(
                    token, pending.bytes, pending.mime, UUID.randomUUID().toString()
                )
                val safeName = pending.name.replace("\"", "")
                val tag = "<attached-image path=\"$path\" name=\"$safeName\" />"
                val fullText = if (text.isEmpty()) tag else "$text\n$tag"
                client.send(token, botId, tid, fullText)
            }
        }
        // Local echo so the image shows right away, even before the
        // server's copy comes back over the stream.
        val key = "local:$sendNonce"
        ImageStore.put(key, pending.bytes)
        val echo = ChatMsg(
            id = "local-$sendNonce",
            mine = true,
            text = text,
            timeMs = System.currentTimeMillis(),
            images = listOf(ChatImage("local", key, pending.name, pending.mime))
        )
        runOnUiThread {
            localEchoes.add(echo)
            messages.add(echo)
            adapter.notifyDataSetChanged()
            listMessages.setSelection(messages.size - 1)
        }
    }

    // ---- Image picking and preparation ----

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Choose an image"), REQ_PICK_IMAGE)
        } catch (e: Exception) {
            Toast.makeText(this, "No image picker found.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            bg.execute {
                try {
                    val pending = prepareImage(uri)
                    runOnUiThread { setPending(pending) }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, e.message ?: "Could not read that image.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setPending(pending: PendingImage) {
        pendingImage = pending
        pendingRow.visibility = View.VISIBLE
        ivPending.setImageBitmap(pending.thumb)
    }

    private fun clearPending() {
        pendingImage = null
        pendingRow.visibility = View.GONE
        ivPending.setImageDrawable(null)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads a picked image and prepares it for upload, following the
     * official Rakazo mobile app's shape: GIF/WebP are kept as-is;
     * photos larger than 1600px on the long edge are resized and saved
     * as JPEG quality 0.7 (PNG stays PNG, HEIC becomes JPEG). Both
     * servers accept at most 10 MB per image.
     */
    private fun prepareImage(uri: Uri): PendingImage {
        val rawMime = contentResolver.getType(uri) ?: ""
        val displayName = queryDisplayName(uri)
        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ApiException("Could not read the image.")
        if (raw.size > 10 * 1024 * 1024) {
            throw ApiException("That image is over 10 MB — pick a smaller one.")
        }
        if (rawMime == "image/gif" || rawMime == "image/webp") {
            val name = displayName ?: if (rawMime == "image/gif") "image.gif" else "image.webp"
            return PendingImage(raw, rawMime, name, decodeSampled(raw, 256))
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw ApiException("Could not read that image.")
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longEdge > 1600) {
            val scale = 1600f / longEdge
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        val mime: String
        if (rawMime == "image/png") {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            mime = "image/png"
        } else {
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            mime = "image/jpeg"
        }
        val bytes = out.toByteArray()
        if (scaled !== bitmap) bitmap.recycle()
        if (bytes.size > 10 * 1024 * 1024) {
            throw ApiException("That image is over 10 MB — pick a smaller one.")
        }
        val baseName = displayName?.let {
            if (it.endsWith(".heic", ignoreCase = true) || it.endsWith(".heif", ignoreCase = true)) {
                it.substringBeforeLast('.') + ".jpg"
            } else it
        } ?: if (mime == "image/png") "image.png" else "photo.jpg"
        return PendingImage(bytes, mime, baseName, decodeSampled(bytes, 256))
    }

    /** Fetches an image's bytes and sets them on the view, unless the row was recycled. */
    private fun loadImage(image: ChatImage, iv: ImageView) {
        val key = imageCacheKey(image, threadId)
        bg.execute {
            try {
                val bytes = ImageFetch.bytes(this, image, threadId)
                val bitmap = decodeSampled(bytes, 1024)
                runOnUiThread {
                    if (iv.tag == key && bitmap != null) iv.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                // Leave the grey placeholder; tapping still opens the
                // viewer, which shows the error.
            }
        }
    }

    private inner class MsgAdapter : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@ChatActivity).inflate(R.layout.item_message, parent, false)
            val msg = messages[position]
            val tvBody = view.findViewById<TextView>(R.id.tvBody)
            val tvTime = view.findViewById<TextView>(R.id.tvTime)
            val container = view.findViewById<LinearLayout>(R.id.imageContainer)
            container.removeAllViews()
            if (msg.images.isEmpty()) {
                container.visibility = View.GONE
            } else {
                container.visibility = View.VISIBLE
                val thumbH = (resources.displayMetrics.density * 220).toInt()
                for (image in msg.images) {
                    val iv = ImageView(this@ChatActivity)
                    iv.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, thumbH
                    ).apply {
                        bottomMargin = (resources.displayMetrics.density * 6).toInt()
                    }
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    iv.setBackgroundColor(Color.parseColor("#D8DEE6"))
                    iv.tag = imageCacheKey(image, threadId)
                    iv.setOnClickListener {
                        ImageViewActivity.open(this@ChatActivity, kind, image, botId, threadId)
                    }
                    container.addView(iv)
                    loadImage(image, iv)
                }
            }
            if (msg.text.isEmpty()) {
                tvBody.visibility = View.GONE
            } else {
                tvBody.visibility = View.VISIBLE
                tvBody.text = msg.text
            }
            val bodyParams = tvBody.layoutParams as LinearLayout.LayoutParams
            val timeParams = tvTime.layoutParams as LinearLayout.LayoutParams
            if (msg.mine) {
                bodyParams.gravity = Gravity.END
                timeParams.gravity = Gravity.END
                tvBody.setBackgroundResource(R.drawable.bubble_mine)
                tvBody.setTextColor(Color.WHITE)
            } else {
                bodyParams.gravity = Gravity.START
                timeParams.gravity = Gravity.START
                tvBody.setBackgroundResource(R.drawable.bubble_bot)
                tvBody.setTextColor(Color.parseColor("#12263F"))
            }
            tvBody.layoutParams = bodyParams
            tvTime.layoutParams = timeParams
            if (msg.timeMs != null) {
                tvTime.visibility = View.VISIBLE
                tvTime.text = TIME_FMT.format(Date(msg.timeMs))
            } else {
                tvTime.visibility = View.GONE
            }
            return view
        }
    }

    companion object {
        private const val REQ_PICK_IMAGE = 1001
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    /** A picked image, prepared for upload, with a small thumbnail for the preview row. */
    private data class PendingImage(
        val bytes: ByteArray,
        val mime: String,
        val name: String,
        val thumb: Bitmap?
    )
}
