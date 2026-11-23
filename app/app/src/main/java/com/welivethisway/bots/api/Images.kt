package com.welivethisway.bots.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/**
 * Images in chat: a small in-memory cache of raw image bytes plus the
 * per-server fetch for a ChatImage. Nothing is written to disk — the
 * cache lives only while the app runs, and the server stays the source
 * of truth for chat history.
 */
object ImageStore {
    private const val MAX_ENTRIES = 12
    private val map = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized fun put(key: String, bytes: ByteArray) { map[key] = bytes }
    @Synchronized fun get(key: String): ByteArray? = map[key]
}

/** Canonical cache key for a ChatImage (screen images also need the thread). */
fun imageCacheKey(image: ChatImage, threadId: String?): String = when (image.kind) {
    "rakazo" -> "rakazo:${image.ref}"
    "mausbot" -> "mausbot:${image.ref.substringAfterLast('/').substringBefore('?')}"
    "mausbot-screen" -> "mausbot-screen:${threadId ?: ""}:${image.ref}"
    else -> image.ref // "local:<uuid>" is already a key
}

object ImageFetch {
    /**
     * Returns the image's raw bytes, from the cache when present, else
     * from the server. Throws ApiException on failure.
     */
    fun bytes(context: Context, image: ChatImage, threadId: String?): ByteArray {
        val key = imageCacheKey(image, threadId)
        ImageStore.get(key)?.let { return it }
        val raw: ByteArray = when (image.kind) {
            "rakazo" -> {
                val base = Prefs.baseUrl(context, ServerKind.RAKAZO)
                    ?: throw ApiException("Rakazo address not set.")
                val token = SecureStore.get(context, SecureStore.KEY_RAKAZO_TOKEN)
                    ?: throw ApiException("Not signed in to Rakazo.")
                val space = SecureStore.get(context, SecureStore.KEY_RAKAZO_SPACE)
                    ?: throw ApiException("No Rakazo space — sign in again from Settings.")
                val b64 = RakazoClient(base).artifactContentBase64(token, space, image.ref)
                try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: Exception) {
                    throw ApiException("Could not download the image.")
                }
            }
            "mausbot" -> {
                val base = Prefs.baseUrl(context, ServerKind.MAUSBOT)
                    ?: throw ApiException("MausBot address not set.")
                val token = SecureStore.get(context, SecureStore.KEY_MAUSBOT_TOKEN)
                    ?: throw ApiException("MausBot not paired.")
                MausBotClient(base).attachmentBytes(token, image.ref)
            }
            "mausbot-screen" -> {
                val base = Prefs.baseUrl(context, ServerKind.MAUSBOT)
                    ?: throw ApiException("MausBot address not set.")
                val token = SecureStore.get(context, SecureStore.KEY_MAUSBOT_TOKEN)
                    ?: throw ApiException("MausBot not paired.")
                val tid = threadId ?: throw ApiException("This bot has no chat thread yet.")
                MausBotClient(base).messageImageBytes(token, tid, image.ref)
            }
            else -> throw ApiException("Could not load the image.")
        }
        ImageStore.put(key, raw)
        return raw
    }
}

/** Decodes image bytes into a Bitmap no larger than maxEdge on its long side. Null when undecodable. */
fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        val longEdge = maxOf(w, h)
        while (longEdge / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (e: Exception) {
        null
    }
}
