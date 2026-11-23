package com.welivethisway.bots.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Rakazo client: oRPC over HTTP.
 * Every call: POST {base}/rpc/{procedure}, body {"json": input},
 * response {"json": output}. Headers: Authorization Bearer, Origin rakazo://,
 * x-rakazo-space-id, x-rakazo-client-build: mobile/<build id>.
 * Verified against the local Rakazo source (packages/contracts, apps/mobile).
 */
class RakazoClient(val base: String) {
    private val origin = base.trimEnd('/')

    private fun headers(token: String?, spaceId: String?): Map<String, String> {
        val h = mutableMapOf(
            "Content-Type" to "application/json",
            "Origin" to "rakazo://",
            "x-rakazo-client-build" to "mobile/${BuildInfo.RAKAZO_BUILD_ID}"
        )
        if (token != null) h["Authorization"] = "Bearer $token"
        if (spaceId != null) h["x-rakazo-space-id"] = spaceId
        return h
    }

    /** Throws ApiException with the server's message when the call fails. */
    fun rpc(procedure: String, token: String?, spaceId: String?, input: JSONObject): JSONObject {
        val resp = Http.request(
            "POST", "$origin/rpc/$procedure", headers(token, spaceId),
            JSONObject().put("json", input).toString()
        )
        if (resp.code == 426) {
            throw ApiException("This app version is too old for the server. Update the app.")
        }
        val parsed = try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            null
        }
        val envelope = parsed?.optJSONObject("json")
        if (resp.code !in 200..299) {
            val msg = envelope?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: parsed?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: "Server error (HTTP ${resp.code})"
            throw ApiException(msg, resp.code)
        }
        return envelope ?: parsed ?: JSONObject()
    }

    fun health(): Boolean {
        return try {
            rpc("health", null, null, JSONObject()).optBoolean("ok", false)
        } catch (e: Exception) {
            false
        }
    }

    /** Returns the bearer token, or throws ApiException with the server's message. */
    fun signIn(email: String, password: String): String {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val resp = Http.request(
            "POST", "$origin/api/auth/sign-in/email",
            mapOf("Content-Type" to "application/json", "Origin" to "rakazo://"), body
        )
        if (resp.code !in 200..299) {
            val parsed = try {
                JSONObject(resp.body)
            } catch (e: Exception) {
                null
            }
            val msg = parsed?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: "Sign-in failed (HTTP ${resp.code})"
            throw ApiException(msg)
        }
        // Token: set-auth-token header first, then the session cookie, then the body.
        resp.header("set-auth-token")?.takeIf { it.isNotBlank() }?.let { return it }
        for (cookie in resp.headerAll("Set-Cookie")) {
            Regex("better-auth\\.session_token=([^;]+)").find(cookie)?.groupValues
                ?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val parsed = try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            null
        }
        parsed?.optString("token")?.takeIf { it.isNotEmpty() }?.let { return it }
        parsed?.optJSONObject("session")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { return it }
        throw ApiException("Signed in, but no session token came back.")
    }

    fun spacesList(token: String): JSONObject = rpc("spaces/list", token, null, JSONObject())

    /** Returns (space id, bots) from a spaces/list response. */
    fun botsFromSpaces(spacesJson: JSONObject): Pair<String?, List<BotItem>> {
        val current = spacesJson.optJSONObject("current") ?: return null to emptyList()
        val spaceId = current.optString("id").takeIf { it.isNotEmpty() }
        val arr = current.optJSONArray("bots") ?: JSONArray()
        val bots = ArrayList<BotItem>()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            bots += BotItem(
                kind = ServerKind.RAKAZO,
                id = b.optString("id"),
                name = b.optString("name").ifEmpty { "Bot" },
                // Description only — the model lives in the detail line now.
                subtitle = b.optString("description").ifEmpty { b.optString("preview") },
                colorHex = b.optString("color").takeIf { it.isNotEmpty() },
                unread = b.optBoolean("unread", false),
                threadId = b.optString("threadId").takeIf { it.isNotEmpty() },
                preview = b.optString("preview").takeIf { it.isNotEmpty() },
                model = b.optString("modelId").takeIf { it.isNotEmpty() },
                // Server name from the address in Settings (the host
                // decides), so the detail line says the real box.
                serverNameOverride = serverNameFromUrl(base, ServerKind.RAKAZO),
                avatarSet = b.optString("avatarSet").takeIf { it.isNotEmpty() },
                updatedAt = parseTimeValue(b.opt("updatedAt")),
                pinned = b.optBoolean("pinned", false)
            )
        }
        return spaceId to bots
    }

    /** updatedAt may be epoch millis or an ISO string. */
    private fun parseTimeValue(v: Any?): Long? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is Number) return v.toLong()
        return parseTime(v.toString())
    }

    fun threadHead(token: String, spaceId: String, botId: String): JSONObject =
        rpc("threads/head", token, spaceId, JSONObject().put("botId", botId))

    fun threadGet(token: String, spaceId: String, botId: String): JSONObject =
        rpc("threads/get", token, spaceId, JSONObject().put("botId", botId))

    fun threadMessages(token: String, spaceId: String, botId: String, before: Long? = null): JSONObject {
        val input = JSONObject().put("botId", botId)
        if (before != null) input.put("before", before)
        return rpc("threads/messages", token, spaceId, input)
    }

    fun threadSend(
        token: String, spaceId: String, botId: String, text: String,
        artifactIds: List<String> = emptyList(), imageHandling: String? = null
    ): JSONObject {
        val input = JSONObject()
            .put("botId", botId)
            .put("text", text)
            .put("clientNonce", UUID.randomUUID().toString())
        if (artifactIds.isNotEmpty()) {
            input.put("artifactIds", JSONArray(artifactIds))
            if (imageHandling != null) input.put("imageHandling", imageHandling)
        }
        return rpc("threads/send", token, spaceId, input)
    }

    /**
     * Uploads one image as an artifact (artifacts/create). Returns the
     * artifact id to pass as an artifactId in threads/send.
     * Verified against the Rakazo source (packages/contracts artifacts-rpc):
     * base64 content, 10 MB/file default, jpeg/png/webp/gif allowed.
     */
    fun createArtifact(
        token: String, spaceId: String, botId: String,
        name: String, mimeType: String, contentBase64: String, clientNonce: String
    ): String {
        val out = rpc(
            "artifacts/create", token, spaceId,
            JSONObject()
                .put("botId", botId)
                .put("name", name)
                .put("mimeType", mimeType)
                .put("contentBase64", contentBase64)
                .put("clientNonce", clientNonce)
        )
        return out.optString("id").takeIf { it.isNotEmpty() }
            ?: throw ApiException("Upload finished but no artifact id came back.")
    }

    /** Fetches an artifact's bytes as base64 (artifacts/getById). */
    fun artifactContentBase64(token: String, spaceId: String, artifactId: String): String {
        val out = rpc(
            "artifacts/getById", token, spaceId,
            JSONObject().put("artifactId", artifactId)
        )
        return out.optString("contentBase64").takeIf { it.isNotEmpty() }
            ?: throw ApiException("Could not download the image.")
    }

    /** Best effort — failures are ignored. */
    fun markRead(token: String, spaceId: String, botId: String) {
        try {
            rpc("threads/markRead", token, spaceId, JSONObject().put("botId", botId))
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * SSE: POST threads/subscribe; calls onEvent for each non-heartbeat
     * event. Reconnect is the caller's job.
     */
    fun subscribe(
        token: String, spaceId: String, botId: String, cursor: Long,
        onConnect: ((HttpURLConnection) -> Unit)?,
        isStopped: () -> Boolean,
        onEvent: () -> Unit
    ) {
        val input = JSONObject().put("botId", botId).put("cursor", cursor)
        val body = JSONObject().put("json", input).toString()
        val h = headers(token, spaceId).toMutableMap()
        h["Accept"] = "text/event-stream"
        Http.stream("POST", "$origin/rpc/threads/subscribe", h, body, onConnect, isStopped) { payload ->
            val ev = try {
                JSONObject(payload).optJSONObject("json")
            } catch (e: Exception) {
                null
            }
            val type = ev?.optString("type") ?: ""
            if (type.isNotEmpty() && type != "heartbeat") onEvent()
            true
        }
    }

    /**
     * Parses a threads/get snapshot (or threads/messages page) into chat
     * messages. Text blocks render fully; image blocks become ChatImages
     * (fetched on demand); file blocks and approval cards are placeholders.
     * Returns (messages, cursor).
     */
    fun parseMessages(snapshot: JSONObject): Pair<List<ChatMsg>, Long> {
        val arr = snapshot.optJSONArray("messages") ?: JSONArray()
        val list = ArrayList<ChatMsg>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val blocks = m.optJSONArray("blocks") ?: JSONArray()
            val sb = StringBuilder()
            val images = ArrayList<ChatImage>()
            for (j in 0 until blocks.length()) {
                val b = blocks.optJSONObject(j) ?: continue
                val piece = when (b.optString("kind")) {
                    "text" -> b.optString("text")
                    "image" -> {
                        val artifactId = b.optString("artifactId")
                        if (artifactId.isNotEmpty()) {
                            images += ChatImage(
                                kind = "rakazo",
                                ref = artifactId,
                                name = b.optString("name").ifEmpty { "image" },
                                mime = b.optString("mimeType").takeIf { it.isNotEmpty() }
                            )
                        }
                        ""
                    }
                    "file" -> "[file: ${b.optString("name").ifEmpty { "file" }}]"
                    "progress" -> "[working…]"
                    "ask", "choice", "tool_question", "mcp_approval" ->
                        "[needs approval — answer in the Rakazo app for now]"
                    else -> ""
                }
                if (piece.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(piece)
                }
            }
            val text = sb.toString()
            if (text.isEmpty() && images.isEmpty()) continue
            list += ChatMsg(
                id = m.optString("id"),
                mine = m.optString("role") == "user",
                text = text,
                timeMs = parseTime(m.optString("createdAt")),
                images = images
            )
        }
        return list to snapshot.optLong("cursor", -1)
    }

    /** True when the snapshot carries an active run (the bot is working). */
    fun isWorking(snapshot: JSONObject): Boolean = snapshot.optJSONObject("run") != null

    /** createdAt may be an ISO string or epoch millis; null when unparseable. */
    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrEmpty()) return null
        raw.toLongOrNull()?.let { return it }
        return try {
            val clean = raw.substringBefore('.').removeSuffix("Z").replace("T", " ")
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(clean)?.time
        } catch (e: Exception) {
            null
        }
    }
}
