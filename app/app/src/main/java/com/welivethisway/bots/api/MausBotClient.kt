package com.welivethisway.bots.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.UUID

/**
 * OpenMausBot client: plain REST + SSE.
 * Auth is pairing: POST /api/pair {code} or {credential} -> session token.
 * Verified against the local OpenMausBot source (v0.1.170; the operator runs 0.1.161).
 * Direct HTTPS (or http for LAN / Tailscale) only — the MausBot Relay E2E
 * protocol is out of scope for v1.
 */
class MausBotClient(val base: String) {
    private val origin = base.trimEnd('/')

    private fun authHeaders(token: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")

    fun health(): Boolean {
        return try {
            val resp = Http.request("GET", "$origin/api/health")
            resp.code == 200 && JSONObject(resp.body).optString("app") == "openmausbot"
        } catch (e: Exception) {
            false
        }
    }

    /** Pair with a 12-char code (dashes/spaces OK) or an omb_pair_ credential. Returns the session token. */
    fun pair(input: String): String {
        val trimmed = input.trim()
        val body = JSONObject()
        val isCredential = trimmed.startsWith("omb_pair_")
        if (isCredential) {
            body.put("credential", trimmed)
        } else {
            body.put("code", trimmed.replace(Regex("[\\s-]"), "").uppercase())
        }
        body.put("deviceName", "WLTW Bots")
        val resp = Http.request(
            "POST", "$origin/api/pair",
            mapOf("Content-Type" to "application/json"), body.toString()
        )
        val parsed = try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            null
        }
        if (resp.code !in 200..299) {
            // Fallback: the older /api/auth/pair route takes the same code.
            if (!isCredential) {
                val alt = Http.request(
                    "POST", "$origin/api/auth/pair",
                    mapOf("Content-Type" to "application/json"),
                    JSONObject().put("code", trimmed.replace(Regex("[\\s-]"), "").uppercase()).toString()
                )
                val altParsed = try {
                    JSONObject(alt.body)
                } catch (e: Exception) {
                    null
                }
                altParsed?.optString("token")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val msg = parsed?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: parsed?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: "Pairing failed (HTTP ${resp.code})"
            throw ApiException(msg)
        }
        return parsed?.optString("token")?.takeIf { it.isNotEmpty() }
            ?: throw ApiException("Paired, but no session token came back.")
    }

    fun listBots(token: String): List<BotItem> {
        // ?messages=20 rides the latest messages along in the same call
        // (server/routes/threads.ts: the bots response merges each bot's
        // selected-thread message page), so the row can show the last
        // thing the operator said instead of the bot's title.
        val resp = Http.request("GET", "$origin/api/bots?messages=20", authHeaders(token))
        if (resp.code == 401) throw ApiException("Pairing expired — pair again.")
        if (resp.code !in 200..299) throw ApiException("Could not list bots (HTTP ${resp.code})")
        val arr = JSONObject(resp.body).optJSONArray("bots") ?: JSONArray()
        val bots = ArrayList<BotItem>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val threadId = b.optString("threadId").takeIf { it.isNotEmpty() }
                ?: b.optString("mainThreadId").takeIf { it.isNotEmpty() }
            val model = b.optJSONObject("modelSelection")?.optString("model") ?: ""
            // Most recent activity across the bot's tasks (newest first):
            // WireTask.updatedAt is epoch ms of the newest message, else
            // createdAt; lastThreadOrderAt is the official thread ordering.
            var lastActivity: Long? = null
            // Working = a task with an active turn right now. WireTask
            // carries runtime-only `busy` / `activity` ("working"); the
            // other activity values ("waiting-on-you", "no-signal",
            // "idle", "parked.computer") mean it is NOT working.
            var working = false
            val tasks = b.optJSONArray("tasks") ?: JSONArray()
            for (j in 0 until tasks.length()) {
                val t = tasks.optJSONObject(j) ?: continue
                for (key in listOf("updatedAt", "lastThreadOrderAt", "createdAt")) {
                    val v = t.optLong(key, 0)
                    if (v > 0) lastActivity = maxOf(lastActivity ?: 0, v)
                }
                if (t.optBoolean("busy", false) || t.optString("activity") == "working") {
                    working = true
                }
            }
            bots += BotItem(
                kind = ServerKind.MAUSBOT,
                id = b.optString("id"),
                name = b.optString("name").ifEmpty { "Bot" },
                // The last thing the operator said to this bot (his last order);
                // the title stays as the fallback when he hasn't said
                // anything to it yet.
                subtitle = lastOrderSubtitle(lastUserText(b)).ifEmpty { b.optString("title") },
                colorHex = b.optString("color").takeIf { it.isNotEmpty() },
                unread = b.optBoolean("unread", false),
                threadId = threadId,
                preview = null,
                model = model.takeIf { it.isNotEmpty() },
                // Server name from the address in Settings (the host
                // decides), so the detail line says the real box.
                serverNameOverride = serverNameFromUrl(base, ServerKind.MAUSBOT),
                working = working,
                pinned = b.optBoolean("pinned", false),
                avatarUrl = b.optString("avatarUrl").takeIf { it.isNotEmpty() },
                avatarCrop = b.optString("avatarCrop").takeIf { it.isNotEmpty() },
                avatarZoom = b.optDouble("avatarZoom", 1.0).toFloat().coerceIn(1f, 3f),
                avatarFocusX = b.optDouble("avatarFocusX", 0.5).toFloat().coerceIn(0f, 1f),
                avatarFocusY = b.optDouble("avatarFocusY", 0.5).toFloat().coerceIn(0f, 1f),
                mascotBody = b.optString("mascotBody").takeIf { it.isNotEmpty() },
                mascotFaceColor = b.optString("mascotFaceColor").takeIf { it.isNotEmpty() },
                lastActivityAt = lastActivity
            )
        }
        return bots
    }

    fun threadMessages(token: String, threadId: String, limit: Int = 50): List<ChatMsg> {
        val resp = Http.request(
            "GET", "$origin/api/threads/$threadId/messages?limit=$limit", authHeaders(token)
        )
        if (resp.code == 401) throw ApiException("Pairing expired — pair again.")
        if (resp.code !in 200..299) throw ApiException("Could not load messages (HTTP ${resp.code})")
        val arr = JSONObject(resp.body).optJSONArray("messages") ?: JSONArray()
        val list = ArrayList<ChatMsg>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val kind = m.optString("kind")
            val images = ArrayList<ChatImage>()
            // Bot/user image attachments ride in the attachments array.
            val atts = m.optJSONArray("attachments") ?: JSONArray()
            for (j in 0 until atts.length()) {
                val a = atts.optJSONObject(j) ?: continue
                if (a.optString("kind") == "image") {
                    val path = a.optString("path")
                    if (path.isNotEmpty()) {
                        images += ChatImage(
                            kind = "mausbot",
                            ref = path,
                            name = a.optString("name").ifEmpty { "image" },
                            mime = a.optString("mime").takeIf { it.isNotEmpty() }
                        )
                    }
                }
            }
            // A bot screen frame is a message of kind "screen"; its PNG
            // comes from the per-message image route.
            if (kind == "screen") {
                images += ChatImage(
                    kind = "mausbot-screen",
                    ref = m.optString("id"),
                    name = "screen",
                    mime = m.optString("mime").takeIf { it.isNotEmpty() } ?: "image/png"
                )
            }
            val text = when (kind) {
                "text" -> stripAttachmentTags(m.optString("text"))
                "activity" -> {
                    val tool = m.optJSONObject("tool")?.optString("name") ?: ""
                    if (tool.isNotEmpty()) "[ran $tool]" else "[activity]"
                }
                "screen" -> ""
                "options", "secret", "signin" -> "[needs a reply in the MausBot app for now]"
                else -> stripAttachmentTags(m.optString("text")).ifEmpty { "[$kind]" }
            }
            if (text.isEmpty() && images.isEmpty()) continue
            list += ChatMsg(
                id = m.optString("id"),
                mine = m.optString("role") == "user",
                text = text,
                timeMs = if (m.has("at")) m.optLong("at") else null,
                images = images
            )
        }
        return list
    }

    /**
     * The newest user-role text in a bot's message page (the /api/bots
     * ?messages= page, chronological, same shape as threadMessages).
     * Empty when the operator hasn't said anything in that thread yet.
     */
    private fun lastUserText(bot: JSONObject): String {
        val arr = bot.optJSONArray("messages") ?: return ""
        var last = ""
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            if (m.optString("kind") != "text") continue
            val text = stripAttachmentTags(m.optString("text")).trim()
            if (text.isNotEmpty()) last = text
        }
        return last
    }

    /**
     * Removes standalone <attached-image>/<attached-file> tag lines from a
     * message's text. The server strips those tags from what the model sees;
     * they should not show in the chat either.
     */
    private fun stripAttachmentTags(text: String): String {
        if (!text.contains("<attached-")) return text
        val tagLine = Regex("^\\s*<attached-(image|file)\\b[^>]*/?>\\s*$")
        return text.lineSequence()
            .filter { !tagLine.matches(it) }
            .joinToString("\n")
            .trim()
    }

    /**
     * Uploads one image's raw bytes (POST /api/attachments?uploadId=…).
     * Returns the server path to reference in the send tag.
     * Verified against the OpenMausBot source (server/routes/threads.ts):
     * raw bytes, Content-Type must be an image type, 10 MB per image.
     */
    fun uploadAttachment(token: String, bytes: ByteArray, mimeType: String, uploadId: String): String {
        val resp = Http.requestBytes(
            "POST", "$origin/api/attachments?uploadId=$uploadId",
            mapOf("Authorization" to "Bearer $token", "Content-Type" to mimeType),
            bytes, readTimeoutMs = 60_000
        )
        if (resp.code == 401) throw ApiException("Pairing expired — pair again.")
        if (resp.code !in 200..299) {
            val msg = try {
                JSONObject(resp.bytes.toString(Charsets.UTF_8)).optString("error")
            } catch (e: Exception) {
                ""
            }
            throw ApiException(msg.ifEmpty { "Image upload failed (HTTP ${resp.code})" })
        }
        val parsed = try {
            JSONObject(resp.bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
        return parsed?.optString("path")?.takeIf { it.isNotEmpty() }
            ?: throw ApiException("Upload finished but no path came back.")
    }

    /** Downloads an attachment's raw bytes (GET /api/attachments/<name>). */
    fun attachmentBytes(token: String, path: String): ByteArray {
        // The wire path is "/api/attachments/<name>"; bot-side records may
        // carry the server filesystem path instead — either way, the name
        // (basename) is what the GET route takes.
        val name = path.substringAfterLast('/').substringBefore('?')
        if (name.isEmpty()) throw ApiException("Could not download the image.")
        val resp = Http.requestBytes(
            "GET", "$origin/api/attachments/$name",
            mapOf("Authorization" to "Bearer $token")
        )
        if (resp.code == 401) throw ApiException("Pairing expired — pair again.")
        if (resp.code !in 200..299) throw ApiException("Could not download the image (HTTP ${resp.code})")
        return resp.bytes
    }

    /** Downloads a screen message's PNG (GET /api/threads/:id/messages/:messageId/image). */
    fun messageImageBytes(token: String, threadId: String, messageId: String): ByteArray {
        val resp = Http.requestBytes(
            "GET", "$origin/api/threads/$threadId/messages/$messageId/image",
            mapOf("Authorization" to "Bearer $token")
        )
        if (resp.code == 401) throw ApiException("Pairing expired — pair again.")
        if (resp.code !in 200..299) throw ApiException("Could not download the image (HTTP ${resp.code})")
        return resp.bytes
    }

    fun send(token: String, botId: String, threadId: String, text: String) {
        val sendId = UUID.randomUUID().toString().replace("-", "") // 32 chars, within 16-80
        val body = JSONObject().put("threadId", threadId).put("sendId", sendId).put("text", text)
        val resp = Http.request("POST", "$origin/api/bots/$botId/messages", authHeaders(token), body.toString())
        if (resp.code == 401) throw ApiException("Pairing expired — pair again.")
        if (resp.code !in 200..299) {
            val msg = try {
                JSONObject(resp.body).optString("message")
            } catch (e: Exception) {
                ""
            }
            throw ApiException(msg.ifEmpty { "Send failed (HTTP ${resp.code})" })
        }
    }

    /** SSE: GET /api/events; calls onEvent for each data frame. Reconnect is the caller's job. */
    fun events(
        token: String,
        onConnect: ((HttpURLConnection) -> Unit)?,
        isStopped: () -> Boolean,
        onEvent: () -> Unit
    ) {
        val h = mapOf("Authorization" to "Bearer $token", "Accept" to "text/event-stream")
        Http.stream("GET", "$origin/api/events", h, null, onConnect, isStopped) { payload ->
            val ev = try {
                JSONObject(payload)
            } catch (e: Exception) {
                null
            }
            val kind = ev?.optString("kind") ?: ""
            if (kind.isNotEmpty() && kind != "hello") onEvent()
            true
        }
    }
}
