package com.welivethisway.bots.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Bot Hub client: plain REST, bearer member key.
 * Verified against the local Bot Hub source (v0.11.0, src/server.js, src/hub.js).
 *
 *   GET  /api/me, /api/rooms, /api/groups, /api/members, /api/dms
 *   GET  /api/rooms/<room>/messages?since=&limit=
 *   POST /api/rooms/<room>/messages {text, reply_to}
 *   POST /api/dms {member, text?}
 *   Admin: POST /api/admin/invites, GET /api/admin/requests,
 *          POST /api/admin/requests/<id>/approve|deny,
 *          POST /api/admin/members, GET /api/admin/members,
 *          PATCH /api/admin/members/<id> {wake},
 *          POST /api/admin/members/<id>/rotate,
 *          GET|POST /api/admin/groups, DELETE /api/admin/groups/<name>,
 *          POST /api/admin/groups/<name>/members {member, on},
 *          POST /api/admin/rooms/<room>/listeners {member, on},
 *          POST /api/admin/rooms/<room>/roster {member, on}
 *
 * Listening (hub.js setListener): a room listener bot wakes on EVERY post
 * in that room with no @mention needed. The hub only allows it for bots
 * (not humans/guests) with wake on and an omb_bot_id, in members-only
 * rooms, and on the roster for private rooms.
 *
 * The hub API is poll-based — there is no SSE/live endpoint. Callers
 * re-read on open / refresh. The server expands @group tags on post;
 * the app only has to send the text. @everyone/@all/@here are built-in
 * reserved names — they are not groups and cannot be created.
 */
class HubClient(val base: String) {
    private val origin = base.trimEnd('/')

    private fun authHeaders(key: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json")

    /** Parses the hub's {error: "..."} body; 401/403 get plain-word messages. */
    private fun fail(resp: Http.Resp, fallback: String): Nothing {
        val msg = try {
            JSONObject(resp.body).optString("error").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } ?: fallback
        when (resp.code) {
            401 -> throw ApiException("Hub key not accepted — check the key in Settings.", 401)
            403 -> throw ApiException(msg, 403)
            else -> throw ApiException("$msg (HTTP ${resp.code})", resp.code)
        }
    }

    private fun get(key: String, path: String): JSONObject {
        val resp = Http.request("GET", "$origin$path", authHeaders(key))
        if (resp.code !in 200..299) fail(resp, "Hub request failed")
        return try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun post(key: String, path: String, body: JSONObject): JSONObject {
        val resp = Http.request("POST", "$origin$path", authHeaders(key), body.toString())
        if (resp.code !in 200..299) fail(resp, "Hub request failed")
        return try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun patch(key: String, path: String, body: JSONObject): JSONObject {
        val resp = Http.request("PATCH", "$origin$path", authHeaders(key), body.toString())
        if (resp.code !in 200..299) fail(resp, "Hub request failed")
        return try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun me(key: String): HubMe {
        val j = get(key, "/api/me")
        return HubMe(
            name = j.optString("name").ifEmpty { "?" },
            isAdmin = j.optBoolean("admin", false),
            canGroupWake = j.optBoolean("canGroupWake", false),
            canClassWake = j.optBoolean("canClassWake", false)
        )
    }

    /** Rooms + DMs + pending join-request count (admin only, -1 when not admin). */
    data class Overview(
        val rooms: List<HubRoomItem>,
        val dms: List<HubRoomItem>,
        val pendingRequests: Int
    )

    fun overview(key: String): Overview {
        val j = get(key, "/api/rooms")
        return Overview(
            rooms = parseRoomList(j.optJSONArray("rooms"), isDm = false),
            dms = parseRoomList(j.optJSONArray("dms"), isDm = true),
            pendingRequests = if (j.has("pendingRequests")) j.optInt("pendingRequests", 0) else -1
        )
    }

    private fun parseRoomList(arr: JSONArray?, isDm: Boolean): List<HubRoomItem> {
        val out = ArrayList<HubRoomItem>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val last = r.optJSONObject("last")
            val listeners = ArrayList<String>()
            val la = r.optJSONArray("listeners")
            if (la != null) for (k in 0 until la.length()) listeners += la.optString(k)
            val roster: List<String>? = r.optJSONArray("roster")?.let { ra ->
                val names = ArrayList<String>()
                for (k in 0 until ra.length()) names += ra.optString(k)
                names
            }
            out += HubRoomItem(
                room = r.optString("name"),
                title = if (isDm) {
                    r.optString("title").ifEmpty { r.optString("peer").ifEmpty { "Direct message" } }
                } else {
                    "#${r.optString("name")}"
                },
                isDm = isDm,
                topic = r.optString("topic"),
                lastText = last?.optString("text")?.takeIf { it.isNotEmpty() },
                lastAuthor = last?.optString("author")?.takeIf { it.isNotEmpty() },
                lastAtMs = last?.optLong("at")?.takeIf { it != 0L },
                count = r.optInt("messages", 0),
                isPrivate = r.optBoolean("private", false),
                lastId = r.optLong("lastId"),
                visitors = r.optBoolean("visitors", false),
                listeners = listeners,
                roster = roster
            )
        }
        return out
    }

    private fun parseGroups(arr: JSONArray?): List<HubGroup> {
        val out = ArrayList<HubGroup>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val members = ArrayList<String>()
            val ma = g.optJSONArray("members")
            if (ma != null) for (k in 0 until ma.length()) members += ma.optString(k)
            out += HubGroup(name = g.optString("name"), members = members)
        }
        return out
    }

    /** @groups for @-autocomplete and broadcast targets (every member can read this). */
    fun groups(key: String): List<HubGroup> {
        val arr = get(key, "/api/groups").optJSONArray("groups")
        return parseGroups(arr)
    }

    /** Member names, for @-autocomplete and starting a DM. */
    fun memberNames(key: String): List<String> {
        val arr = get(key, "/api/members").optJSONArray("members") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    fun readRoom(key: String, room: String, limit: Int = 50): List<HubMessage> {
        val enc = URLEncoder.encode(room, "UTF-8")
        val j = get(key, "/api/rooms/$enc/messages?limit=$limit")
        return parseMessages(j.optJSONArray("messages"))
    }

    private fun parseMessages(arr: JSONArray?): List<HubMessage> {
        val out = ArrayList<HubMessage>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val mentions = ArrayList<String>()
            val ma = m.optJSONArray("mentions")
            if (ma != null) for (k in 0 until ma.length()) mentions += ma.optString(k)
            val file = m.optJSONObject("file")
            out += HubMessage(
                id = m.optLong("id", 0),
                author = m.optString("author").ifEmpty { "?" },
                text = m.optString("text"),
                mentions = mentions,
                replyTo = if (m.isNull("replyTo")) null else m.optLong("replyTo").takeIf { it != 0L },
                atMs = parseIso(m.optString("at")),
                edited = m.has("edited"),
                visitor = m.optBoolean("visitor", false),
                fileName = file?.optString("name")?.takeIf { it.isNotEmpty() }
            )
        }
        return out
    }

    /** Posts to a room (or a dm- room). Returns the posted message when the server sends one back. */
    fun postRoom(key: String, room: String, text: String, replyTo: Long?): HubMessage? {
        val enc = URLEncoder.encode(room, "UTF-8")
        val body = JSONObject().put("text", text)
        if (replyTo != null) body.put("reply_to", replyTo)
        val j = post(key, "/api/rooms/$enc/messages", body)
        if (!j.has("id")) return null
        return parseMessages(JSONArray().put(j)).firstOrNull()
    }

    fun listDms(key: String): List<HubRoomItem> {
        val j = get(key, "/api/dms")
        return parseRoomList(j.optJSONArray("dms"), isDm = true)
    }

    /** Opens (or returns) a 1:1 DM with a member. Returns room name + title. */
    fun openDm(key: String, member: String, text: String? = null): Pair<String, String> {
        val body = JSONObject().put("member", member)
        if (text != null) body.put("text", text)
        val j = post(key, "/api/dms", body)
        val room = j.optString("room")
        if (room.isEmpty()) throw ApiException("Could not open that DM.")
        val title = j.optString("title").ifEmpty { j.optString("peer").ifEmpty { member } }
        return room to title
    }

    // ---- admin ----

    data class InviteResult(val memberName: String, val key: String?, val text: String)

    /** Creates a visitor invite. The returned text is ready to share as-is. */
    fun invite(key: String, name: String, days: Int): InviteResult {
        val j = post(key, "/api/admin/invites", JSONObject().put("name", name).put("days", days))
        val member = j.optJSONObject("member")
        return InviteResult(
            memberName = member?.optString("name") ?: name,
            key = j.optString("key").takeIf { it.isNotEmpty() },
            text = j.optString("text")
        )
    }

    fun requests(key: String): List<HubJoinRequest> {
        val arr = get(key, "/api/admin/requests").optJSONArray("requests") ?: return emptyList()
        val out = ArrayList<HubJoinRequest>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            out += HubJoinRequest(
                id = r.optLong("id", 0),
                name = r.optString("name"),
                platform = r.optString("platform").takeIf { it.isNotEmpty() },
                note = r.optString("note").takeIf { it.isNotEmpty() },
                contact = r.optString("contact").takeIf { it.isNotEmpty() },
                status = r.optString("status"),
                createdAtMs = if (r.has("createdAt")) r.optLong("createdAt") else null
            )
        }
        return out
    }

    /** Approves a join request. The requester picks up their own key with their claim code — no key comes back here. */
    fun approveRequest(key: String, id: Long, asRole: String, days: Int = 30): String {
        val j = post(
            key, "/api/admin/requests/$id/approve",
            JSONObject().put("days", days).put("as", asRole)
        )
        return j.optJSONObject("member")?.optString("name") ?: "Approved"
    }

    fun denyRequest(key: String, id: Long) {
        post(key, "/api/admin/requests/$id/deny", JSONObject())
    }

    /** Adds a bot (or human) member. The key is shown ONCE — the caller must display it with a copy button. */
    fun addMember(key: String, name: String, kind: String, role: String): Pair<String, String?> {
        val j = post(
            key, "/api/admin/members",
            JSONObject().put("name", name).put("kind", kind).put("role", role)
        )
        val member = j.optJSONObject("member")
        return (member?.optString("name") ?: name) to j.optString("key").takeIf { it.isNotEmpty() }
    }

    fun adminMembers(key: String): List<HubMember> {
        val arr = get(key, "/api/admin/members").optJSONArray("members") ?: return emptyList()
        val out = ArrayList<HubMember>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            out += HubMember(
                id = m.optLong("id", 0),
                name = m.optString("name"),
                kind = m.optString("kind"),
                role = m.optString("role"),
                admin = m.optBoolean("admin", false),
                guest = m.optBoolean("guest", false),
                wake = m.optBoolean("wake", false),
                ombBotId = m.optString("ombBotId").takeIf { it.isNotEmpty() },
                machine = m.optString("machine").takeIf { it.isNotEmpty() }
            )
        }
        return out
    }

    /** Rotates a member's key. The new key is shown ONCE. */
    fun rotateKey(key: String, id: Long): Pair<String, String?> {
        val j = post(key, "/api/admin/members/$id/rotate", JSONObject())
        val member = j.optJSONObject("member")
        return (member?.optString("name") ?: "Member") to j.optString("key").takeIf { it.isNotEmpty() }
    }

    // ---- groups (admin) ----

    /** All groups with their members. Names "class", "all", "everyone", "here" are reserved — @everyone is built in. */
    fun adminGroups(key: String): List<HubGroup> {
        val arr = get(key, "/api/admin/groups").optJSONArray("groups")
        return parseGroups(arr)
    }

    /** Creates a group. Returns it, or null when the server sent nothing back. */
    fun createGroup(key: String, name: String): HubGroup? {
        val j = post(key, "/api/admin/groups", JSONObject().put("name", name))
        val arr = JSONArray()
        if (j.has("name")) arr.put(j)
        return parseGroups(arr).firstOrNull() ?: HubGroup(name, emptyList())
    }

    fun deleteGroup(key: String, name: String) {
        val enc = URLEncoder.encode(name, "UTF-8")
        val resp = Http.request("DELETE", "$origin/api/admin/groups/$enc", authHeaders(key))
        if (resp.code !in 200..299) fail(resp, "Could not delete that group")
    }

    /** Adds (on=true) or removes (on=false) a member. Returns the group's member names after the change. */
    fun setGroupMember(key: String, group: String, member: String, on: Boolean): List<String> {
        val enc = URLEncoder.encode(group, "UTF-8")
        val j = post(
            key, "/api/admin/groups/$enc/members",
            JSONObject().put("member", member).put("on", on)
        )
        val members = ArrayList<String>()
        val ma = j.optJSONArray("members")
        if (ma != null) for (i in 0 until ma.length()) members += ma.optString(i)
        return members
    }

    // ---- listening (admin) ----

    /** Makes a bot always listen in a room (on=true) or only answer when @mentioned (on=false). Returns the room's listener names after the change. */
    fun setListener(key: String, room: String, member: String, on: Boolean): List<String> {
        val enc = URLEncoder.encode(room, "UTF-8")
        val j = post(
            key, "/api/admin/rooms/$enc/listeners",
            JSONObject().put("member", member).put("on", on)
        )
        val out = ArrayList<String>()
        val la = j.optJSONArray("listeners")
        if (la != null) for (i in 0 until la.length()) out += la.optString(i)
        return out
    }

    /** Turns a member's wake-up on or off (PATCH /api/admin/members/<id> {wake}). */
    fun setMemberWake(key: String, id: Long, wake: Boolean) {
        patch(key, "/api/admin/members/$id", JSONObject().put("wake", wake))
    }

    /** Adds (on=true) or removes (on=false) a member from a private room's roster. */
    fun setRoster(key: String, room: String, member: String, on: Boolean) {
        val enc = URLEncoder.encode(room, "UTF-8")
        post(
            key, "/api/admin/rooms/$enc/roster",
            JSONObject().put("member", member).put("on", on)
        )
    }

    companion object {
        private val ISO_FULL = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val ISO_NOMS = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /** Hub message times are ISO strings; null when unparseable. */
        fun parseIso(s: String?): Long? {
            if (s.isNullOrEmpty()) return null
            return try {
                ISO_FULL.parse(s)?.time
            } catch (e: Exception) {
                try {
                    ISO_NOMS.parse(s)?.time
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}
