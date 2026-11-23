package com.welivethisway.bots.api

import android.content.Context

/** Plain settings: the two server addresses. No credentials live here. */
object Prefs {
    private const val NAME = "wltw_bots"
    const val KEY_RAKAZO_BASE = "rakazo_base"
    const val KEY_MAUSBOT_BASE = "mausbot_base"
    const val KEY_HUB_BASE = "hub_base"
    const val KEY_BOT_SORT = "bot_sort"
    const val KEY_HIDDEN_DMS = "hidden_dms"

    /**
     * DMs hidden on this phone only (the hub keeps them — a DM can't be
     * deleted, even by the owner). Map of DM room name -> hub message id
     * of the newest message when it was hidden. A hidden DM comes back
     * when its lastId grows past that (someone sent a new message).
     */
    fun hiddenDms(ctx: Context): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val raw = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN_DMS, null)
            ?: return out
        for (entry in raw) {
            val parts = entry.split("|")
            if (parts.size == 2) {
                parts[1].toLongOrNull()?.let { out[parts[0]] = it }
            }
        }
        return out
    }

    fun saveHiddenDms(ctx: Context, hidden: Map<String, Long>) {
        val set = hidden.entries.map { "${it.key}|${it.value}" }.toSet()
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putStringSet(KEY_HIDDEN_DMS, set).apply()
    }

    fun hideDm(ctx: Context, room: String, lastId: Long) {
        val map = hiddenDms(ctx).toMutableMap()
        map[room] = lastId
        saveHiddenDms(ctx, map)
    }

    fun unhideDm(ctx: Context, room: String) {
        val map = hiddenDms(ctx).toMutableMap()
        if (map.remove(room) != null) saveHiddenDms(ctx, map)
    }

    /** How the bot list is ordered. Default: pinned first, then most recent. */
    enum class BotSort { PINNED_RECENT, RECENT, NAME }

    fun botSort(ctx: Context): BotSort {
        return when (ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_BOT_SORT, null)) {
            "RECENT" -> BotSort.RECENT
            "NAME" -> BotSort.NAME
            else -> BotSort.PINNED_RECENT
        }
    }

    fun setBotSort(ctx: Context, sort: BotSort) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_BOT_SORT, sort.name).apply()
    }

    fun hubBaseUrl(ctx: Context): String? {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_HUB_BASE, null)?.takeIf { it.isNotBlank() }
    }

    fun setHubBaseUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY_HUB_BASE, url).apply()
    }

    fun clearHubBaseUrl(ctx: Context) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_HUB_BASE).apply()
    }

    fun baseUrl(ctx: Context, kind: ServerKind): String? {
        val key = if (kind == ServerKind.RAKAZO) KEY_RAKAZO_BASE else KEY_MAUSBOT_BASE
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(key, null)?.takeIf { it.isNotBlank() }
    }

    fun setBaseUrl(ctx: Context, kind: ServerKind, url: String) {
        val key = if (kind == ServerKind.RAKAZO) KEY_RAKAZO_BASE else KEY_MAUSBOT_BASE
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(key, url).apply()
    }

    fun clearBaseUrl(ctx: Context, kind: ServerKind) {
        val key = if (kind == ServerKind.RAKAZO) KEY_RAKAZO_BASE else KEY_MAUSBOT_BASE
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(key).apply()
    }

    /** Trim whitespace and a trailing slash; require http(s). Null when unusable. */
    fun cleanBaseUrl(raw: String): String? {
        val v = raw.trim().trimEnd('/')
        if (v.isEmpty()) return null
        if (!v.startsWith("http://") && !v.startsWith("https://")) return null
        return v
    }
}
