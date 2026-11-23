package com.welivethisway.bots.api

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String, val code: Int = 0) : Exception(message)

/** Minimal HTTP layer on HttpURLConnection. No third-party libraries. */
object Http {
    data class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>) {
        fun header(name: String): String? {
            for ((k, v) in headers) {
                if (k.equals(name, ignoreCase = true)) return v.firstOrNull()
            }
            return null
        }

        fun headerAll(name: String): List<String> {
            for ((k, v) in headers) {
                if (k.equals(name, ignoreCase = true)) return v
            }
            return emptyList()
        }
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): Resp {
        val r = requestBytes(method, url, headers, body?.toByteArray(Charsets.UTF_8), connectTimeoutMs, readTimeoutMs)
        return Resp(r.code, r.bytes.toString(Charsets.UTF_8), r.headers)
    }

    /** Raw-bytes variant: for binary uploads (MausBot attachments) and downloads (images). */
    data class RespBytes(val code: Int, val bytes: ByteArray, val headers: Map<String, List<String>>)

    fun requestBytes(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): RespBytes {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            for ((k, v) in headers) setRequestProperty(k, v)
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            val h = mutableMapOf<String, List<String>>()
            for ((k, v) in conn.headerFields) if (k != null) h[k] = v
            RespBytes(code, bytes, h)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Opens a streaming request (SSE) and calls [onData] with the payload of
     * each `data:` line. Returns when the stream ends, [isStopped] is true,
     * or [onData] returns false. Throws on connect/IO errors so the caller
     * can reconnect.
     */
    fun stream(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        onConnect: ((HttpURLConnection) -> Unit)? = null,
        isStopped: () -> Boolean,
        onData: (String) -> Boolean
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 90_000
            for ((k, v) in headers) setRequestProperty(k, v)
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            onConnect?.invoke(conn)
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw ApiException("HTTP $code: ${err.take(200)}")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (!isStopped()) {
                val line = try {
                    reader.readLine()
                } catch (e: Exception) {
                    null
                } ?: break
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") {
                        if (!onData(payload)) break
                    }
                }
            }
        } finally {
            try {
                conn.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
