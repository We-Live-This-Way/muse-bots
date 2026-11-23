package com.welivethisway.bots.api

enum class ServerKind { RAKAZO, MAUSBOT }

data class BotItem(
    val kind: ServerKind,
    val id: String,
    val name: String,
    /** List-row line under the name. Starts as the bot's title/description;
     * refresh fills it with the last thing the operator said (see lastOrderSubtitle). */
    var subtitle: String,
    val colorHex: String?,
    val unread: Boolean,
    val threadId: String?,
    val preview: String?,
    /** Raw model id as the server reports it (Rakazo `modelId`, MausBot `modelSelection.model`). */
    val model: String? = null,
    /**
     * Server name as derived from the base URL the user entered in
     * Settings (see `serverNameFromUrl`). The clients fill this in;
     * null falls back to the kind's usual name. MainActivity may
     * override it from the Hub, which knows each bot's real machine.
     */
    var serverNameOverride: String? = null,
    /** True while the bot has an active run (green dot); false = idle (grey dot). */
    var working: Boolean = false,
    /** Rakazo: "organic" | "rakazo" — the logo mark vs the organic blob. */
    val avatarSet: String? = null,
    /** Rakazo: last activity, epoch millis (BotSchema `updatedAt`). */
    val updatedAt: Long? = null,
    /** Rakazo: pinned in the official app. */
    val pinned: Boolean = false,
    /** MausBot: /api/attachments/<name> or null. */
    val avatarUrl: String? = null,
    /** MausBot: "circle" | "rounded" | "square" | "mascot". */
    val avatarCrop: String? = null,
    /** MausBot: zoom 1..3 (absent = 1). */
    val avatarZoom: Float = 1f,
    /** MausBot: focus point 0..1 (absent = center). */
    val avatarFocusX: Float = 0.5f,
    val avatarFocusY: Float = 0.5f,
    /** MausBot: mascot body id (e.g. "curled"), null = default. */
    val mascotBody: String? = null,
    /** MausBot: "white" | "black" or null. */
    val mascotFaceColor: String? = null,
    /** MausBot: most recent activity across the bot's tasks, epoch millis. */
    val lastActivityAt: Long? = null
) {
    /** Most recent activity, whichever server the bot is on. */
    val activityAt: Long? get() = updatedAt ?: lastActivityAt

    /** "Rakazo" / "Mausbot" — the server type in the operator's detail line. */
    val serverTypeLabel: String get() = if (kind == ServerKind.RAKAZO) "Rakazo" else "Mausbot"

    /**
     * "Rakazo" / "OpenMausBot" — the server name. The clients fill
     * this in from the base URL the user entered in Settings (the host
     * decides — a known Rakazo host is labelled "Rakazo"), so a MausBot on
     * the Rakazo host is labelled the Rakazo host, not the OpenMausBot host. Falls back to the
     * kind's usual name when the client didn't supply one.
     */
    val serverName: String get() = serverNameOverride
        ?: if (kind == ServerKind.RAKAZO) "Rakazo" else "OpenMausBot"

    /**
     * the operator's detail line: `Name - Server type - Model - Server name`,
     * e.g. `Alex - Mausbot - DeepSeek Flash 4.1 1017 - Rakazo`.
     * Every value comes live from the server; the model is shown as
     * reported (never hardcoded — his example said 1018, the real one
     * is 1017). Blank parts are dropped, never shown as a gap.
     */
    val detailLine: String
        get() {
            val parts = ArrayList<String>()
            parts += name
            parts += serverTypeLabel
            prettyModel(model)?.let { parts += it }
            parts += serverName
            return parts.joinToString(" - ")
        }
}

/**
 * The list-row line under a bot's name: the last thing the operator said to
 * that bot (his last message = his last order). One line, capped so a
 * long order never blows up the row; blank when there is nothing to
 * show, so the caller can keep the bot's title/description instead.
 */
fun lastOrderSubtitle(text: String?): String {
    val clean = text.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return ""
    val capped = if (clean.length > 140) clean.take(137) + "…" else clean
    return "You: $capped"
}

/**
 * The Hub's machine tag for a bot ("rakazo-host" / "laptop"), mirroring
 * the Hub's own SERVER_LABELS (hub.js): known house machines only —
 * anything else (hostnames, IPs, ad-hoc names) yields null, and the
 * caller keeps the URL-derived name.
 */
fun serverLabelForMachine(machine: String?): String? {
    return when (machine?.trim()?.lowercase()) {
        "maushaus" -> "Rakazo"
        "welive", "laptop" -> "laptop"
        else -> null
    }
}

/**
 * The server name for a base URL the user entered in Settings: the
 * host decides. A known Rakazo address is labelled "Rakazo", an OpenMausBot address is labelled
 * "the OpenMausBot host". Anything else shows the host itself (an IP or custom
 * name is what it is, never a guess). A null/blank URL falls back to
 * the kind's usual name.
 */
fun serverNameFromUrl(url: String?, kind: ServerKind): String {
    val fallback = if (kind == ServerKind.RAKAZO) "Rakazo" else "OpenMausBot"
    val raw = url?.trim().orEmpty()
    if (raw.isEmpty()) return fallback
    val host = raw.substringAfter("://", raw).substringBefore("/").substringBefore(":").lowercase()
    return when {
        host.isEmpty() -> fallback
        "maushaus" in host -> "Rakazo"
        "otto" in host -> "OpenMausBot"
        else -> host
    }
}

/**
 * The model id as the server reports it, made readable:
 * `deepseek-v4-1-flash-1017` -> `DeepSeek Flash 4.1 1017`,
 * `zai-glm-5-3-flash` -> `GLM 5.3 Flash`. Anything unrecognised
 * is returned unchanged — the true id, never a guess. Null when
 * the server reported no model at all.
 */
fun prettyModel(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val deepseek = Regex("deepseek-v(\\d+)-(\\d+)-flash-(.+)", RegexOption.IGNORE_CASE).find(raw.trim())
    if (deepseek != null) {
        val (major, minor, tail) = deepseek.destructured
        return "DeepSeek Flash $major.$minor $tail"
    }
    val glm = Regex("glm-(\\d+)-(\\d+)-flash", RegexOption.IGNORE_CASE).find(raw.trim())
    if (glm != null) {
        val (major, minor) = glm.destructured
        return "GLM $major.$minor Flash"
    }
    return raw.trim()
}

data class ChatMsg(
    val id: String,
    val mine: Boolean,
    val text: String,
    val timeMs: Long?,
    val images: List<ChatImage> = emptyList()
)

/**
 * One image inside a chat message.
 * kind: "rakazo" (ref = artifactId), "mausbot" (ref = attachment path),
 * "mausbot-screen" (ref = messageId; fetched via the message image route),
 * "local" (ref = ImageStore key for an image this app just sent).
 */
data class ChatImage(
    val kind: String,
    val ref: String,
    val name: String,
    val mime: String?
)

// ---- Bot Hub ----

data class HubRoomItem(
    val room: String,
    val title: String,
    val isDm: Boolean,
    val topic: String,
    val lastText: String?,
    val lastAuthor: String?,
    val lastAtMs: Long?,
    val count: Int,
    val isPrivate: Boolean = false,
    /** Hub message id of the newest message (hub `lastId`); 0 when the room has none. Used to tell a hidden DM has new activity. */
    val lastId: Long = 0,
    /** True when the room is open to visitors — listeners are not allowed there. */
    val visitors: Boolean = false,
    /** Bots that always listen in this room (wake on every post, no @mention needed). */
    val listeners: List<String> = emptyList(),
    /** Roster names, only present for private rooms (admin view). Null otherwise. */
    val roster: List<String>? = null
)

data class HubMessage(
    val id: Long,
    val author: String,
    val text: String,
    val mentions: List<String>,
    val replyTo: Long?,
    val atMs: Long?,
    val edited: Boolean,
    val visitor: Boolean,
    val fileName: String?
)

data class HubGroup(val name: String, val members: List<String>)

data class HubJoinRequest(
    val id: Long,
    val name: String,
    val platform: String?,
    val note: String?,
    val contact: String?,
    val status: String,
    val createdAtMs: Long?
)

data class HubMember(
    val id: Long,
    val name: String,
    val kind: String,
    val role: String,
    val admin: Boolean,
    val guest: Boolean,
    /** Wake-up on @mention. Listeners need this on. */
    val wake: Boolean = false,
    /** Linked OpenMausBot bot id. Listeners need this set. */
    val ombBotId: String? = null,
    /** Hub machine tag (host short name / "laptop") — which box this bot runs on. */
    val machine: String? = null
)

data class HubMe(
    val name: String,
    val isAdmin: Boolean,
    val canGroupWake: Boolean,
    val canClassWake: Boolean
)
