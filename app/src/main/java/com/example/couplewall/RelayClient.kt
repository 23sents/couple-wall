package com.example.couplewall

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the couple-wall relay (see /server). The relay only ever sees an unguessable topic name and AES-GCM
 * ciphertext. Every request carries the server key ([appKey]) and a per-pair token derived from the secret code.
 * Only https URLs are accepted.
 */
class RelayClient(baseUrl: String, private val appKey: String) {
    private val baseUrl = baseUrl.trim().trimEnd('/')
    @Volatile private var connection: HttpURLConnection? = null

    val isConfigured: Boolean get() = baseUrl.startsWith("https://", ignoreCase = true) && baseUrl.length > 12

    private fun open(path: String): HttpURLConnection {
        if (!isConfigured) throw IOException("relay url is not configured")
        return URL("$baseUrl/$path").openConnection() as HttpURLConnection
    }

    private fun HttpURLConnection.authorize(token: String) {
        if (appKey.isNotEmpty()) setRequestProperty("X-App-Key", appKey)
        setRequestProperty("X-Pair-Token", token)
    }

    /** Publishes one message; false on any failure (network, rate limit, auth). */
    fun publish(topic: String, token: String, text: String): Boolean {
        val c = try { open(topic) } catch (e: IOException) { return false }
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.authorize(token)
            val body = text.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body) }
            val ok = c.responseCode in 200..299
            try { (if (ok) c.inputStream else c.errorStream)?.close() } catch (e: IOException) { }
            ok
        } catch (e: IOException) {
            false
        } finally {
            c.disconnect()
        }
    }

    /**
     * Streams messages (newline-delimited JSON) until the connection drops, [active] turns false or [abort] is called.
     * [since] is "all" or the id of the last message already processed.
     */
    fun subscribe(
        topic: String,
        token: String,
        since: String,
        active: () -> Boolean,
        onOpen: () -> Unit,
        onMessage: (id: String, text: String) -> Unit,
    ) {
        val c = open("$topic/json?since=" + java.net.URLEncoder.encode(since, "UTF-8"))
        connection = c
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.authorize(token)
            if (c.responseCode != 200) throw IOException("http ${c.responseCode}")
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (active()) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    when (o.optString("event")) {
                        "open" -> onOpen()
                        "message" -> onMessage(o.optString("id"), o.optString("message"))
                    }
                }
            }
        } finally {
            connection = null
            c.disconnect()
        }
    }

    fun abort() {
        try { connection?.disconnect() } catch (e: Exception) { }
    }
}
